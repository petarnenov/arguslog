import {
  IconBrandAngular,
  IconBrandJavascript,
  IconBrandNextjs,
  IconBrandNodejs,
  IconBrandPython,
  IconBrandReact,
  IconBrandReactNative,
  IconBrandVue,
  IconCode,
  IconCoffee,
  type IconProps,
} from '@tabler/icons-react';
import type { ComponentType } from 'react';

/**
 * Platform → visual mapping for project cards / dropdowns. Colors come from each ecosystem's
 * canonical brand palette (React cyan, Vue green, Angular red, …) so a row in a project list
 * is recognisable at a glance instead of having to read the slug. {@code unknown} platforms
 * fall back to a neutral {@code IconCode} on gray.
 */
export interface PlatformVisuals {
  Icon: ComponentType<IconProps>;
  color: string;
}

const VISUALS: Record<string, PlatformVisuals> = {
  javascript: { Icon: IconBrandJavascript, color: 'yellow' },
  react: { Icon: IconBrandReact, color: 'cyan' },
  vue: { Icon: IconBrandVue, color: 'green' },
  angular: { Icon: IconBrandAngular, color: 'red' },
  nextjs: { Icon: IconBrandNextjs, color: 'gray' },
  'react-native': { Icon: IconBrandReactNative, color: 'cyan' },
  node: { Icon: IconBrandNodejs, color: 'green' },
  'java-spring': { Icon: IconCoffee, color: 'orange' },
  python: { Icon: IconBrandPython, color: 'blue' },
};

export function platformVisuals(slug: string): PlatformVisuals {
  return VISUALS[slug] ?? { Icon: IconCode, color: 'gray' };
}
