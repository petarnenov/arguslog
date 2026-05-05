import type { Breadcrumb } from './types.js';

export class BreadcrumbBuffer {
  private readonly buffer: Breadcrumb[] = [];

  constructor(private readonly max: number) {}

  add(crumb: Breadcrumb): void {
    this.buffer.push(crumb);
    if (this.buffer.length > this.max) this.buffer.shift();
  }

  snapshot(): Breadcrumb[] {
    return this.buffer.slice();
  }

  clear(): void {
    this.buffer.length = 0;
  }
}
