interface Props {
  error: Error;
  reset: () => void;
}

/**
 * Rendered by {@link ArguslogErrorBoundary} when a descendant throws. The boundary has already
 * reported the error to Arguslog via {@code captureException} with the {@code boundary:react} tag
 * by the time this UI shows, so this component is purely about giving the user a recovery path.
 */
export function ErrorFallback({ error, reset }: Props) {
  return (
    <div className="error-fallback">
      <h2>Something went wrong in this section.</h2>
      <p>
        The error was reported to Arguslog. You can reset this boundary and continue, or reload the
        page if the state feels stuck.
      </p>
      <pre className="error-message">{error.message}</pre>
      <div className="row">
        <button type="button" onClick={reset}>
          Reset boundary
        </button>
        <button type="button" onClick={() => window.location.reload()}>
          Reload page
        </button>
      </div>
    </div>
  );
}
