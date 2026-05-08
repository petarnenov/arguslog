# @arguslog/sdk-vue

Arguslog SDK for Vue 3 apps.

## Install

```bash
pnpm add @arguslog/sdk-vue
```

## Plugin bootstrap

```ts
import { createApp } from 'vue';
import { createArguslog } from '@arguslog/sdk-vue';

import App from './App.vue';

createApp(App)
  .use(
    createArguslog({
      dsn: 'arguslog://<key>@<host>/api/<projectId>',
      release: '1.0.0',
      environment: 'production',
      integrations: ['globalHandlers'],
    }),
  )
  .mount('#app');
```

`createArguslog` runs `init(options)`, registers `app.config.errorHandler`
to forward all uncaught Vue component errors to `captureException`, and
provides an `ArguslogService` instance for `useArguslog()`.

If you maintain your own `errorHandler` chain, pass `attachErrorHandler: false`:

```ts
app.use(createArguslog({ dsn, attachErrorHandler: false }));
app.config.errorHandler = (err, instance, info) => {
  captureException(err, { tags: { framework: 'vue', vueInfo: info } });
  // …your handler…
};
```

## Capturing manually

```vue
<script setup lang="ts">
import { useArguslog } from '@arguslog/sdk-vue';

const arguslog = useArguslog();

async function pay(): Promise<void> {
  try {
    // …
  } catch (err) {
    arguslog.captureException(err, { tags: { flow: 'checkout' } });
  }
}
</script>
```

## Error boundaries

Wrap a subtree with `<ArguslogErrorBoundary>` to catch render and lifecycle
errors and render a fallback:

```vue
<script setup lang="ts">
import { ArguslogErrorBoundary } from '@arguslog/sdk-vue';
</script>

<template>
  <ArguslogErrorBoundary>
    <Checkout />
    <template #fallback="{ error, reset }">
      <p>Something went wrong: {{ error.message }}</p>
      <button @click="reset">Try again</button>
    </template>
  </ArguslogErrorBoundary>
</template>
```

The boundary supports both a slot/render-function `fallback` and a static
`fallback` prop, mirroring the React SDK's API.
