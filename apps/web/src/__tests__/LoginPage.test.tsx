import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import '../i18n';
import { LoginPage } from '../pages/LoginPage';

describe('LoginPage', () => {
  it('renders the app name and login button', () => {
    render(
      <MantineProvider>
        <MemoryRouter>
          <LoginPage />
        </MemoryRouter>
      </MantineProvider>,
    );
    expect(screen.getByText('Arguslog')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
  });
});
