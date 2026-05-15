import { Menu, useMantineColorScheme, type MantineColorScheme } from '@mantine/core';
import { IconCheck, IconDeviceDesktop, IconMoon, IconSun } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

const OPTIONS: ReadonlyArray<{ value: MantineColorScheme; Icon: typeof IconSun; key: string }> = [
  { value: 'light', Icon: IconSun, key: 'theme.light' },
  { value: 'dark', Icon: IconMoon, key: 'theme.dark' },
  { value: 'auto', Icon: IconDeviceDesktop, key: 'theme.auto' },
];

/**
 * Theme picker fragment for the top-right user Menu. Rendered as a fragment so it lives inside
 * the existing `<Menu.Dropdown>` portal — the parent must wrap it in a Menu / Menu.Dropdown.
 */
export function ThemeMenuSection() {
  const { t } = useTranslation();
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <>
      <Menu.Divider />
      <Menu.Label>{t('theme.section')}</Menu.Label>
      {OPTIONS.map(({ value, Icon, key }) => {
        const active = colorScheme === value;
        return (
          <Menu.Item
            key={value}
            leftSection={<Icon size={14} />}
            rightSection={active ? <IconCheck size={12} aria-hidden /> : null}
            aria-current={active ? 'true' : undefined}
            onClick={() => setColorScheme(value)}
            closeMenuOnClick={false}
          >
            {t(key)}
          </Menu.Item>
        );
      })}
    </>
  );
}
