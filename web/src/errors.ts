export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'The request failed.';
}
