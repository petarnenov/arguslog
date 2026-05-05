import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import '../i18n';
import { LoginPage } from '../pages/LoginPage';

describe('LoginPage', () => {
  it('renders the app name and login button', () => {
    render(
      <MantineProvider>
        <LoginPage />
      </MantineProvider>,
    );
    expect(screen.getByText('Argus')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
  });
});
