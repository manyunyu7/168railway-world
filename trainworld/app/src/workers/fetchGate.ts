// 168Railway: at most `limit` downloads at a time, the most urgent first. A game with many cities open asked
// for every zone's water mask and street arrays at once (several hundred requests on load); on a phone's
// line each crawled, hit its time limit, was given up and asked for again, so none finished and every edit
// waiting on a mask stayed unanswered ("the figures stopped updating"). Queued, each download has the line.

/** lower runs first: what an edit or load waits for before what only refines it */
export const Urgency = { Waited: 0, Terrain: 1, Streets: 2 } as const;

export class FetchGate {
  private running = 0;
  private queue: { urgency: number; seq: number; go: () => void }[] = [];
  private seq = 0;
  constructor(private readonly limit: number) {}

  /** `job` started when a place is free, most urgent (then oldest) first; its result passed through */
  run<T>(urgency: number, job: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const go = () => {
        this.running++;
        let p: Promise<T>;
        try { p = job(); } catch (err) { p = Promise.reject(err); }
        p.then(resolve, reject).finally(() => { this.running--; this.next(); });
      };
      this.queue.push({ urgency, seq: this.seq++, go });
      this.queue.sort((a, b) => a.urgency - b.urgency || a.seq - b.seq);
      this.next();
    });
  }

  /** downloads waiting for a place, and those running (for diagnostics) */
  get size() { return { waiting: this.queue.length, running: this.running }; }

  private next() {
    while (this.running < this.limit && this.queue.length) this.queue.shift()!.go();
  }
}
