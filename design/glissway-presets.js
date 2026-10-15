/* Local controller presets. MIDI connections, theme and sensor calibration stay outside this store. */
(function (global) {
  'use strict';

  const VERSION = 1;
  const MAX_NAME_LENGTH = 80;

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function comparable(value) {
    if (Array.isArray(value)) return value.map(comparable);
    if (value && typeof value === 'object') {
      const sorted = {};
      Object.keys(value).sort().forEach(key => { sorted[key] = comparable(value[key]); });
      return sorted;
    }
    return value;
  }

  function equal(a, b) {
    return JSON.stringify(comparable(a)) === JSON.stringify(comparable(b));
  }

  function normalizeName(value) {
    if (typeof value !== 'string') throw new Error('Enter a preset name.');
    const name = value.normalize('NFC').trim();
    if (!name) throw new Error('Preset names cannot be empty.');
    if (Array.from(name).length > MAX_NAME_LENGTH) throw new Error('Preset names must be 80 characters or fewer.');
    if (/[\u0000-\u001f\u007f]/.test(name)) throw new Error('Preset names cannot contain control characters.');
    return name;
  }

  function nameKey(name) {
    return name.normalize('NFC').toLocaleLowerCase();
  }

  function create(options) {
    options = options || {};
    const storage = options.storage || global.localStorage;
    const key = options.key || 'glissway-presets';
    const report = error => {
      if (typeof options.onError === 'function') {
        try { options.onError(error); } catch (_) { /* A notice must not hide the storage error. */ }
      }
    };
    const checked = action => {
      try { return action(); } catch (error) { report(error); throw error; }
    };
    const config = value => {
      let result = clone(value);
      if (typeof options.validateConfig === 'function') result = options.validateConfig(result);
      if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('Invalid preset configuration.');
      return clone(result);
    };
    const makeId = () => {
      let id;
      do {
        id = global.crypto && typeof global.crypto.randomUUID === 'function'
          ? global.crypto.randomUUID()
          : 'preset-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 11);
      } while (state && state.presets.some(preset => preset.id === id));
      return id;
    };
    const now = () => new Date().toISOString();
    let state;

    const initial = checked(() => config(options.initialConfig));
    const freshState = () => {
      const timestamp = now();
      const preset = { id: makeId(), name: 'Default', config: clone(initial), createdAt: timestamp, updatedAt: timestamp };
      return { version: VERSION, presets: [preset], currentId: preset.id, working: clone(initial) };
    };
    const commit = next => {
      // Change the in-memory state only after the single persistent write succeeds.
      storage.setItem(key, JSON.stringify(next));
      state = next;
    };
    const currentRecord = () => state.presets.find(preset => preset.id === state.currentId);
    const find = id => {
      const preset = state.presets.find(entry => entry.id === id);
      if (!preset) throw new Error('This preset no longer exists.');
      return preset;
    };
    const uniqueName = (value, exceptId) => {
      const name = normalizeName(value);
      if (state.presets.some(preset => preset.id !== exceptId && nameKey(preset.name) === nameKey(name))) {
        throw new Error('A preset with this name already exists.');
      }
      return name;
    };

    checked(() => {
      const raw = storage.getItem(key);
      if (raw === null) {
        commit(freshState());
        return;
      }
      let recovered = false;
      let next;
      try {
        const saved = JSON.parse(raw);
        if (!saved || saved.version !== VERSION || !Array.isArray(saved.presets)) throw new Error('Unsupported preset data.');
        const ids = new Set();
        const names = new Set();
        const presets = [];
        saved.presets.forEach(entry => {
          try {
            if (!entry || typeof entry.id !== 'string' || !entry.id || ids.has(entry.id)) throw new Error('Invalid preset ID.');
            const name = normalizeName(entry.name);
            if (names.has(nameKey(name))) throw new Error('Duplicate preset name.');
            const preset = {
              id: entry.id,
              name,
              config: config(entry.config),
              createdAt: typeof entry.createdAt === 'string' ? entry.createdAt : now(),
              updatedAt: typeof entry.updatedAt === 'string' ? entry.updatedAt : now()
            };
            ids.add(preset.id);
            names.add(nameKey(name));
            presets.push(preset);
          } catch (_) { recovered = true; }
        });
        if (!presets.length) throw new Error('No readable presets were found.');
        const current = presets.find(preset => preset.id === saved.currentId) || presets[0];
        if (current.id !== saved.currentId) recovered = true;
        let working;
        try { working = config(saved.working); } catch (_) { working = clone(current.config); recovered = true; }
        next = { version: VERSION, presets, currentId: current.id, working };
      } catch (_) {
        recovered = true;
        next = freshState();
      }
      if (recovered) {
        // Preserve unreadable data before replacing it, including when a storage write fails.
        storage.setItem(key + '-recovery-' + Date.now(), raw);
        commit(next);
        report(new Error('Some preset data could not be read. A local recovery copy was preserved.'));
      } else {
        state = next;
      }
    });

    return {
      list: () => clone(state.presets),
      current: () => clone(currentRecord()),
      working: () => clone(state.working),
      dirty: value => !equal(config(value === undefined ? state.working : value), currentRecord().config),
      checkpoint: value => checked(() => {
        const working = config(value);
        if (!equal(working, state.working)) commit(Object.assign({}, state, { working }));
        return clone(state.working);
      }),
      save: value => checked(() => {
        const working = config(value === undefined ? state.working : value);
        const next = clone(state);
        const preset = next.presets.find(entry => entry.id === next.currentId);
        preset.config = clone(working);
        preset.updatedAt = now();
        next.working = working;
        commit(next);
        return clone(preset);
      }),
      saveAs: (value, settings) => checked(() => {
        const name = uniqueName(value);
        const working = config(settings === undefined ? state.working : settings);
        const timestamp = now();
        const preset = { id: makeId(), name, config: clone(working), createdAt: timestamp, updatedAt: timestamp };
        const next = clone(state);
        next.presets.push(preset);
        next.currentId = preset.id;
        next.working = working;
        commit(next);
        return clone(preset);
      }),
      load: id => checked(() => {
        const preset = find(id);
        const next = Object.assign({}, state, { currentId: id, working: clone(preset.config) });
        commit(next);
        return clone(next.working);
      }),
      rename: (id, value) => checked(() => {
        find(id);
        const name = uniqueName(value, id);
        const next = clone(state);
        const preset = next.presets.find(entry => entry.id === id);
        preset.name = name;
        preset.updatedAt = now();
        commit(next);
        return clone(preset);
      }),
      duplicate: (id, value) => checked(() => {
        const original = find(id);
        let name;
        if (value !== undefined) {
          name = uniqueName(value);
        } else {
          const base = Array.from(original.name).slice(0, MAX_NAME_LENGTH - 12).join('');
          let suffix = 1;
          do {
            name = base + (suffix === 1 ? ' Copy' : ' Copy ' + suffix);
            suffix += 1;
          } while (state.presets.some(preset => nameKey(preset.name) === nameKey(name)));
        }
        const timestamp = now();
        const preset = { id: makeId(), name, config: clone(original.config), createdAt: timestamp, updatedAt: timestamp };
        const next = clone(state);
        next.presets.push(preset);
        commit(next);
        return clone(preset);
      }),
      remove: id => checked(() => {
        find(id);
        let next = clone(state);
        const currentChanged = next.currentId === id;
        next.presets = next.presets.filter(preset => preset.id !== id);
        if (!next.presets.length) {
          next = freshState();
        } else if (currentChanged) {
          next.currentId = next.presets[0].id;
          next.working = clone(next.presets[0].config);
        }
        commit(next);
        return { currentChanged, config: clone(state.working), current: clone(currentRecord()) };
      })
    };
  }

  global.GlisswayPresetStore = { create };
})(window);
