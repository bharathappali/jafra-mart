import { gantt } from './charts.js';

const SUBSYSTEMS = {
  1: 'CPU', 2: 'Heap', 3: 'GC', 4: 'Metaspace', 5: 'Code cache', 6: 'Native',
  7: 'Container mem', 8: 'Locks', 9: 'Threads', 10: 'VThreads', 11: 'Safepoints',
  12: 'JIT', 13: 'Class loading', 14: 'Socket I/O', 15: 'File I/O', 16: 'Exceptions',
};

export function subsystemName(n) {
  return SUBSYSTEMS[n] || String(n);
}

export function renderTimeline(container, timeline, groundTruth, run) {
  const timeScale = run?.timeScale || 1.0;
  const durationMin = 30 * timeScale;
  const rows = (timeline || []).map((entry) => {
    const id = entry.id || entry.scenarioId || '?';
    const planned = entry.planned || entry.phases || [];
    const segments = planned.map((p) => ({
      start: (p.startSeconds ?? p.start ?? 0) / 60,
      end: (p.endSeconds ?? p.end ?? 0) / 60,
      phase: (p.phase || p.state || 'hold').toLowerCase().replace('ing', '').replace('ramp', 'ramp').replace('hold', 'hold').replace('recover', 'recover'),
    })).map((s) => {
      let phase = s.phase;
      if (phase.includes('ramp')) phase = 'ramp';
      else if (phase.includes('hold')) phase = 'hold';
      else if (phase.includes('recover')) phase = 'recover';
      return { ...s, phase };
    });
    const markers = [];
    if (Array.isArray(groundTruth)) {
      groundTruth.filter((g) => (g.scenarioId || g.id) === id).forEach((g) => {
        markers.push({ at: (g.elapsedSeconds ?? g.t ?? 0) / 60, label: g.phase || g.state });
      });
    }
    return { label: id, segments, markers };
  });
  const elapsed = run?.elapsedSeconds != null ? run.elapsedSeconds / 60 : null;
  const overlapLabels = (timeline || [])
    .filter((e) => e.overlapGroup)
    .map((e) => ({ at: (e.overlapAtSeconds || 0) / 60, text: e.overlapGroup }));
  gantt(container, rows, { durationMin: Math.max(durationMin, 1), nowMin: elapsed, overlapLabels });
}
