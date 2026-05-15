import { MantineProvider } from '@mantine/core';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import '../../i18n';
import { ThemeToggle } from '../../components/ThemeToggle';

function renderToggle() {
  return render(
    <MantineProvider defaultColorScheme="light">
      <ThemeToggle />
    </MantineProvider>,
  );
}

describe('ThemeToggle', () => {
  it('renders three buttons reachable by accessible name', () => {
    renderToggle();
    expect(screen.getByRole('button', { name: 'Light' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Auto' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dark' })).toBeInTheDocument();
  });

  it('marks the currently-active scheme via aria-pressed', () => {
    renderToggle();
    // MantineProvider boots with defaultColorScheme="light" + no manager → useMantineColorScheme
    // reports 'light' on first render so the Light button is the pressed one.
    expect(screen.getByRole('button', { name: 'Light' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('switches the active button when the user clicks another scheme', () => {
    renderToggle();

    fireEvent.click(screen.getByRole('button', { name: 'Dark' }));

    expect(screen.getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Light' })).toHaveAttribute('aria-pressed', 'false');
  });
});
