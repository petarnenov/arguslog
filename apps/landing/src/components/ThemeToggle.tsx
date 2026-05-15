import { ActionIcon, Tooltip, useMantineColorScheme, type MantineColorScheme } from '@mantine/core';
import { IconDeviceDesktop, IconMoon, IconSun } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

const OPTIONS: ReadonlyArray<{ value: MantineColorScheme; Icon: typeof IconSun; key: string }> = [
  { value: 'light', Icon: IconSun, key: 'theme.light' },
  { value: 'auto', Icon: IconDeviceDesktop, key: 'theme.auto' },
  { value: 'dark', Icon: IconMoon, key: 'theme.dark' },
];

export function ThemeToggle() {
  const { t } = useTranslation();
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <ActionIcon.Group>
      {OPTIONS.map(({ value, Icon, key }) => {
        const active = colorScheme === value;
        const label = t(key);
        return (
          <Tooltip key={value} label={label} withArrow>
            <ActionIcon
              variant={active ? 'filled' : 'subtle'}
              color={active ? 'green' : 'gray'}
              size="lg"
              aria-label={label}
              aria-pressed={active}
              onClick={() => setColorScheme(value)}
            >
              <Icon size={16} />
            </ActionIcon>
          </Tooltip>
        );
      })}
    </ActionIcon.Group>
  );
}
