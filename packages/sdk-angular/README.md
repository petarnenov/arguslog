# @arguslog/sdk-angular

Arguslog SDK for Angular 17+ apps.

## Install

```bash
pnpm add @arguslog/sdk-angular
```

## Standalone bootstrap

```ts
import { bootstrapApplication } from '@angular/platform-browser';
import { provideArguslog } from '@arguslog/sdk-angular';

import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent, {
  providers: [
    provideArguslog({
      dsn: 'arguslog://<key>@<host>/api/<projectId>',
      release: '1.0.0',
      environment: 'production',
      integrations: ['globalHandlers'],
    }),
  ],
});
```

`provideArguslog` registers an `ErrorHandler` that forwards all uncaught
Angular errors to `captureException`, and runs `init(options)` during
environment initialization.

## NgModule bootstrap

```ts
import { ArguslogModule } from '@arguslog/sdk-angular';

@NgModule({
  imports: [
    ArguslogModule.forRoot({
      dsn: 'arguslog://<key>@<host>/api/<projectId>',
    }),
  ],
})
export class AppModule {}
```

## Capturing manually

Inject `ArguslogService` (or import the function helpers from
`@arguslog/sdk-browser` directly):

```ts
import { Component, inject } from '@angular/core';
import { ArguslogService } from '@arguslog/sdk-angular';

@Component({
  /* … */
})
export class CheckoutComponent {
  private readonly arguslog = inject(ArguslogService);

  pay(): void {
    try {
      // …
    } catch (err) {
      this.arguslog.captureException(err, { tags: { flow: 'checkout' } });
    }
  }
}
```
