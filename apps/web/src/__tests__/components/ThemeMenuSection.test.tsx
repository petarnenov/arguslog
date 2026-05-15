import { MantineProvider, Menu } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import '../../i18n';
import { ThemeMenuSection } from '../../components/ThemeMenuSection';

function renderInOpenMenu() {
  return render(
    <MantineProvider defaultColorScheme="light">
      {/* `defaultOpened` keeps the Dropdown rendered without needing to click the target. */}
      <Menu defaultOpened closeOnItemClick={false}>
        <Menu.Target>
          <button type="button">trigger</button>
        </Menu.Target>
        <Menu.Dropdown>
          <ThemeMenuSection />
        </Menu.Dropdown>
      </Menu>
    </MantineProvider>,
  );
}

describe('ThemeMenuSection', () => {
  it('renders Light / Dark / Auto items reachable by accessible name', () => {
    renderInOpenMenu();
    expect(screen.getByRole('menuitem', { name: /Light/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Dark/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Auto/i })).toBeInTheDocument();
  });

  it('marks the current scheme with aria-current="true"', () => {
    renderInOpenMenu();
    expect(screen.getByRole('menuitem', { name: /Light/i })).toHaveAttribute(
      'aria-current',
      'true',
    );
    expect(screen.getByRole('menuitem', { name: /Dark/i })).not.toHaveAttribute('aria-current');
  });

  it('moves the active marker when the user picks another scheme', async () => {
    const user = userEvent.setup();
    renderInOpenMenu();

    await user.click(screen.getByRole('menuitem', { name: /Dark/i }));

    expect(screen.getByRole('menuitem', { name: /Dark/i })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('menuitem', { name: /Light/i })).not.toHaveAttribute('aria-current');
  });
});
