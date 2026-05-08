/*
 * Arguslog Mobile Design — Figma Plugin
 *
 * Generates the Arguslog mobile-first design system in the active Figma file:
 *   - Design tokens preview (color, type, spacing)
 *   - 7 screens at 375x812: Login, Onboarding, Issues, Issue Detail,
 *     Releases, Alerts, Plan & Billing
 *
 * Run via Figma → Plugins → Development → Arguslog Mobile Design.
 */

// ============================================================================
// TOKENS
// ============================================================================
function hex(s: string): RGB {
  const h = s.replace('#', '');
  return {
    r: parseInt(h.slice(0, 2), 16) / 255,
    g: parseInt(h.slice(2, 4), 16) / 255,
    b: parseInt(h.slice(4, 6), 16) / 255,
  };
}

const C = {
  primary: hex('#4F46E5'),
  primaryDark: hex('#4338CA'),
  primarySoft: hex('#EEF2FF'),
  accent: hex('#7C3AED'),

  bg: hex('#FFFFFF'),
  surface: hex('#F9FAFB'),
  surfaceAlt: hex('#F3F4F6'),
  border: hex('#E5E7EB'),
  borderStrong: hex('#D1D5DB'),

  text: hex('#111827'),
  textSub: hex('#6B7280'),
  textMute: hex('#9CA3AF'),
  textOnPrimary: hex('#FFFFFF'),

  error: hex('#DC2626'),
  errorBg: hex('#FEF2F2'),
  warn: hex('#D97706'),
  warnBg: hex('#FFFBEB'),
  success: hex('#059669'),
  successBg: hex('#ECFDF5'),
  info: hex('#2563EB'),
  infoBg: hex('#EFF6FF'),

  codeBg: hex('#0F172A'),
  codeBorder: hex('#1E293B'),
  codeText: hex('#E2E8F0'),
  codeMute: hex('#64748B'),
  codeKey: hex('#A5B4FC'),
  codeStr: hex('#86EFAC'),
  codeFn: hex('#FCD34D'),
};

const R = { sm: 6, md: 8, lg: 12, xl: 16, pill: 999 };

// ============================================================================
// FONT LOADING (with graceful fallbacks)
// ============================================================================
let MONO_FAMILY = 'JetBrains Mono';

async function loadFonts() {
  const sans = ['Regular', 'Medium', 'Semi Bold', 'Bold'];
  for (const s of sans) {
    await figma.loadFontAsync({ family: 'Inter', style: s });
  }
  const monoCandidates = ['JetBrains Mono', 'Roboto Mono', 'Source Code Pro', 'Inter'];
  for (const fam of monoCandidates) {
    try {
      await figma.loadFontAsync({ family: fam, style: 'Regular' });
      await figma.loadFontAsync({ family: fam, style: 'Medium' }).catch(() => {});
      MONO_FAMILY = fam;
      return;
    } catch {
      // try next
    }
  }
}

const F = (style: string = 'Regular'): FontName => ({ family: 'Inter', style });
const M = (style: string = 'Regular'): FontName => ({ family: MONO_FAMILY, style });

// ============================================================================
// HELPERS
// ============================================================================
type Pad = number | [number, number] | [number, number, number, number];

interface FOpts {
  dir?: 'V' | 'H';
  gap?: number;
  pad?: Pad;
  fill?: RGB;
  fillOpacity?: number;
  noFill?: boolean;
  w?: number | 'FILL' | 'HUG';
  h?: number | 'FILL' | 'HUG';
  main?: 'MIN' | 'CENTER' | 'MAX' | 'SPACE_BETWEEN';
  cross?: 'MIN' | 'CENTER' | 'MAX';
  radius?: number;
  border?: { color: RGB; width: number };
  shadow?: 'sm' | 'md' | 'lg';
  clip?: boolean;
}

function setPad(f: FrameNode, p: Pad) {
  if (typeof p === 'number') {
    f.paddingTop = p;
    f.paddingBottom = p;
    f.paddingLeft = p;
    f.paddingRight = p;
  } else if (p.length === 2) {
    f.paddingTop = p[0];
    f.paddingBottom = p[0];
    f.paddingLeft = p[1];
    f.paddingRight = p[1];
  } else {
    f.paddingTop = p[0];
    f.paddingRight = p[1];
    f.paddingBottom = p[2];
    f.paddingLeft = p[3];
  }
}

function applySize(f: FrameNode, opts: FOpts) {
  if (f.layoutMode === 'NONE') {
    if (typeof opts.w === 'number' || typeof opts.h === 'number') {
      f.resize(
        typeof opts.w === 'number' ? opts.w : f.width,
        typeof opts.h === 'number' ? opts.h : f.height,
      );
    }
    // For non-auto-layout children of auto-layout parents, STRETCH fills the
    // cross axis of the parent (horizontal in V parent, vertical in H parent).
    if (opts.w === 'FILL' || opts.h === 'FILL') {
      f.layoutAlign = 'STRETCH';
    }
    return;
  }
  if (opts.w === 'FILL') f.layoutSizingHorizontal = 'FILL';
  else if (typeof opts.w === 'number') {
    f.resize(opts.w, f.height);
    f.layoutSizingHorizontal = 'FIXED';
  } else f.layoutSizingHorizontal = 'HUG';

  if (opts.h === 'FILL') f.layoutSizingVertical = 'FILL';
  else if (typeof opts.h === 'number') {
    f.resize(f.width, opts.h);
    f.layoutSizingVertical = 'FIXED';
  } else f.layoutSizingVertical = 'HUG';
}

function frame(name: string, opts: FOpts = {}, ...children: SceneNode[]): FrameNode {
  const f = figma.createFrame();
  f.name = name;
  if (opts.dir) f.layoutMode = opts.dir === 'H' ? 'HORIZONTAL' : 'VERTICAL';
  if (opts.gap !== undefined) f.itemSpacing = opts.gap;
  if (opts.pad !== undefined) setPad(f, opts.pad);
  if (opts.main) f.primaryAxisAlignItems = opts.main;
  if (opts.cross) f.counterAxisAlignItems = opts.cross;

  if (opts.noFill) f.fills = [];
  else if (opts.fill)
    f.fills = [{ type: 'SOLID', color: opts.fill, opacity: opts.fillOpacity ?? 1 }];

  if (opts.radius !== undefined) f.cornerRadius = opts.radius;
  if (opts.border) {
    f.strokes = [{ type: 'SOLID', color: opts.border.color }];
    f.strokeWeight = opts.border.width;
  }
  if (opts.shadow) {
    const map = {
      sm: { y: 1, blur: 2, alpha: 0.05 },
      md: { y: 4, blur: 12, alpha: 0.08 },
      lg: { y: 12, blur: 32, alpha: 0.12 },
    };
    const s = map[opts.shadow];
    f.effects = [
      {
        type: 'DROP_SHADOW',
        color: { r: 0, g: 0, b: 0, a: s.alpha },
        offset: { x: 0, y: s.y },
        radius: s.blur,
        spread: 0,
        visible: true,
        blendMode: 'NORMAL',
      },
    ];
  }
  if (opts.clip !== undefined) f.clipsContent = opts.clip;

  for (const c of children) f.appendChild(c);
  applySize(f, opts);

  // Post-process: wrap text nodes that were marked at creation time.
  if (f.layoutMode !== 'NONE') {
    for (const c of f.children) {
      if (c.type === 'TEXT' && c.getPluginData(WRAP_MARK) === '1') {
        c.textAutoResize = 'HEIGHT';
        c.layoutSizingHorizontal = 'FILL';
      }
    }
  }

  return f;
}

interface TOpts {
  size?: number;
  weight?: 'Regular' | 'Medium' | 'Semi Bold' | 'Bold';
  mono?: boolean;
  color?: RGB;
  lineHeight?: number;
  letterSpacing?: number;
  align?: 'LEFT' | 'CENTER' | 'RIGHT';
  wrap?: boolean;
}

const WRAP_MARK = '__alWrap';

function txt(content: string, opts: TOpts = {}): TextNode {
  const t = figma.createText();
  t.fontName = opts.mono ? M(opts.weight ?? 'Regular') : F(opts.weight ?? 'Regular');
  t.fontSize = opts.size ?? 14;
  t.characters = content;
  t.fills = [{ type: 'SOLID', color: opts.color ?? C.text }];
  if (opts.lineHeight) t.lineHeight = { value: opts.lineHeight, unit: 'PIXELS' };
  if (opts.letterSpacing !== undefined)
    t.letterSpacing = { value: opts.letterSpacing, unit: 'PIXELS' };
  if (opts.align) t.textAlignHorizontal = opts.align;
  if (opts.wrap) t.setPluginData(WRAP_MARK, '1');
  return t;
}

function spacer(size: number, vertical = true): FrameNode {
  const f = figma.createFrame();
  f.name = 'spacer';
  f.fills = [];
  f.resize(vertical ? 1 : size, vertical ? size : 1);
  return f;
}

function dot(size: number, color: RGB): EllipseNode {
  const e = figma.createEllipse();
  e.resize(size, size);
  e.fills = [{ type: 'SOLID', color }];
  e.name = 'dot';
  return e;
}

function divider(): FrameNode {
  return frame('divider', { fill: C.border, h: 1, w: 'FILL' });
}

// ============================================================================
// COMPONENTS
// ============================================================================
function pillBadge(label: string, fg: RGB, bg: RGB): FrameNode {
  return frame(
    'badge',
    { dir: 'H', pad: [4, 10], radius: R.pill, fill: bg, cross: 'CENTER' },
    txt(label, { size: 11, weight: 'Semi Bold', color: fg, letterSpacing: 0.4 }),
  );
}

function chip(label: string, active = false): FrameNode {
  return frame(
    'chip',
    {
      dir: 'H',
      pad: [6, 12],
      radius: R.pill,
      fill: active ? C.text : C.surfaceAlt,
      border: active ? undefined : { color: C.border, width: 1 },
      cross: 'CENTER',
      gap: 6,
    },
    txt(label, {
      size: 12,
      weight: 'Medium',
      color: active ? C.bg : C.textSub,
    }),
  );
}

function button(
  label: string,
  variant: 'primary' | 'secondary' | 'ghost' = 'primary',
  fullWidth = true,
): FrameNode {
  const fills = {
    primary: {
      bg: C.primary,
      fg: C.textOnPrimary,
      border: undefined as { color: RGB; width: number } | undefined,
    },
    secondary: { bg: C.bg, fg: C.text, border: { color: C.border, width: 1 } },
    ghost: {
      bg: C.surface,
      fg: C.text,
      border: undefined as { color: RGB; width: number } | undefined,
    },
  }[variant];
  return frame(
    `btn-${variant}`,
    {
      dir: 'H',
      pad: [14, 20],
      radius: R.md,
      fill: fills.bg,
      border: fills.border,
      main: 'CENTER',
      cross: 'CENTER',
      w: fullWidth ? 'FILL' : 'HUG',
    },
    txt(label, { size: 15, weight: 'Semi Bold', color: fills.fg }),
  );
}

function topBar(title: string, opts: { back?: boolean; right?: SceneNode[] } = {}): FrameNode {
  const left = frame(
    'left',
    { dir: 'H', gap: 12, cross: 'CENTER' },
    ...(opts.back ? [iconChevronLeft()] : []),
    txt(title, { size: 17, weight: 'Semi Bold' }),
  );
  const right = frame('right', { dir: 'H', gap: 12, cross: 'CENTER' }, ...(opts.right ?? []));
  return frame(
    'top-bar',
    {
      dir: 'H',
      pad: [14, 20],
      w: 'FILL',
      fill: C.bg,
      cross: 'CENTER',
      main: 'SPACE_BETWEEN',
      border: { color: C.border, width: 1 },
    },
    left,
    right,
  );
}

function bottomTabBar(active: 'issues' | 'releases' | 'alerts' | 'settings'): FrameNode {
  function tab(key: string, label: string, icon: SceneNode) {
    const isActive = key === active;
    const color = isActive ? C.primary : C.textMute;
    if ('fills' in icon) (icon as FrameNode).fills = [{ type: 'SOLID', color }];
    return frame(
      `tab-${key}`,
      { dir: 'V', gap: 4, cross: 'CENTER', w: 'FILL', pad: [8, 0] },
      icon,
      txt(label, { size: 11, weight: 'Medium', color }),
    );
  }
  return frame(
    'bottom-tab-bar',
    {
      dir: 'H',
      pad: [8, 12, 24, 12],
      w: 'FILL',
      fill: C.bg,
      border: { color: C.border, width: 1 },
    },
    tab('issues', 'Issues', iconBug()),
    tab('releases', 'Releases', iconTag()),
    tab('alerts', 'Alerts', iconBell()),
    tab('settings', 'Settings', iconCog()),
  );
}

// ============================================================================
// ICONS (simple geometric placeholders, 20x20)
// ============================================================================
function iconBox(): FrameNode {
  return frame('icon', { w: 20, h: 20, noFill: true });
}

function iconChevronLeft(): FrameNode {
  const f = iconBox();
  const r1 = figma.createRectangle();
  r1.resize(2, 10);
  r1.x = 8;
  r1.y = 5;
  r1.rotation = 45;
  r1.cornerRadius = 1;
  r1.fills = [{ type: 'SOLID', color: C.text }];
  const r2 = figma.createRectangle();
  r2.resize(2, 10);
  r2.x = 8;
  r2.y = 5;
  r2.rotation = -45;
  r2.cornerRadius = 1;
  r2.fills = [{ type: 'SOLID', color: C.text }];
  // approximate chevron via two rects
  r1.x = 11;
  r1.y = 4;
  r2.x = 11;
  r2.y = 10;
  f.appendChild(r1);
  f.appendChild(r2);
  return f;
}

function iconSearch(): FrameNode {
  const f = iconBox();
  const ring = figma.createEllipse();
  ring.resize(13, 13);
  ring.x = 2;
  ring.y = 2;
  ring.fills = [];
  ring.strokes = [{ type: 'SOLID', color: C.text }];
  ring.strokeWeight = 1.6;
  const tail = figma.createRectangle();
  tail.resize(2, 7);
  tail.x = 13;
  tail.y = 12;
  tail.rotation = -45;
  tail.cornerRadius = 1;
  tail.fills = [{ type: 'SOLID', color: C.text }];
  f.appendChild(ring);
  f.appendChild(tail);
  return f;
}

function iconFilter(): FrameNode {
  const f = iconBox();
  for (let i = 0; i < 3; i++) {
    const r = figma.createRectangle();
    r.resize(16 - i * 4, 2);
    r.x = 2 + i * 2;
    r.y = 4 + i * 5;
    r.cornerRadius = 1;
    r.fills = [{ type: 'SOLID', color: C.text }];
    f.appendChild(r);
  }
  return f;
}

function iconBug(): FrameNode {
  const f = iconBox();
  const body = figma.createEllipse();
  body.resize(12, 14);
  body.x = 4;
  body.y = 3;
  body.fills = [{ type: 'SOLID', color: C.text }];
  f.appendChild(body);
  return f;
}

function iconTag(): FrameNode {
  const f = iconBox();
  const r = figma.createRectangle();
  r.resize(13, 13);
  r.x = 2;
  r.y = 2;
  r.cornerRadius = 2;
  r.rotation = -45;
  r.fills = [{ type: 'SOLID', color: C.text }];
  f.appendChild(r);
  return f;
}

function iconBell(): FrameNode {
  const f = iconBox();
  const body = figma.createEllipse();
  body.resize(12, 12);
  body.x = 4;
  body.y = 3;
  body.fills = [{ type: 'SOLID', color: C.text }];
  const handle = figma.createRectangle();
  handle.resize(4, 2);
  handle.x = 8;
  handle.y = 16;
  handle.cornerRadius = 1;
  handle.fills = [{ type: 'SOLID', color: C.text }];
  f.appendChild(body);
  f.appendChild(handle);
  return f;
}

function iconCog(): FrameNode {
  const f = iconBox();
  const ring = figma.createEllipse();
  ring.resize(14, 14);
  ring.x = 3;
  ring.y = 3;
  ring.fills = [{ type: 'SOLID', color: C.text }];
  const inner = figma.createEllipse();
  inner.resize(5, 5);
  inner.x = 7.5;
  inner.y = 7.5;
  inner.fills = [{ type: 'SOLID', color: C.bg }];
  f.appendChild(ring);
  f.appendChild(inner);
  return f;
}

function iconCopy(): FrameNode {
  const f = iconBox();
  const back = figma.createRectangle();
  back.resize(11, 11);
  back.x = 6;
  back.y = 2;
  back.cornerRadius = 1.5;
  back.fills = [];
  back.strokes = [{ type: 'SOLID', color: C.text }];
  back.strokeWeight = 1.4;
  const front = figma.createRectangle();
  front.resize(11, 11);
  front.x = 3;
  front.y = 7;
  front.cornerRadius = 1.5;
  front.fills = [{ type: 'SOLID', color: C.bg }];
  front.strokes = [{ type: 'SOLID', color: C.text }];
  front.strokeWeight = 1.4;
  f.appendChild(back);
  f.appendChild(front);
  return f;
}

function iconShare(): FrameNode {
  const f = iconBox();
  const a = figma.createEllipse();
  a.resize(5, 5);
  a.x = 13;
  a.y = 2;
  a.fills = [{ type: 'SOLID', color: C.text }];
  const b = figma.createEllipse();
  b.resize(5, 5);
  b.x = 2;
  b.y = 8;
  b.fills = [{ type: 'SOLID', color: C.text }];
  const c = figma.createEllipse();
  c.resize(5, 5);
  c.x = 13;
  c.y = 13;
  c.fills = [{ type: 'SOLID', color: C.text }];
  f.appendChild(a);
  f.appendChild(b);
  f.appendChild(c);
  return f;
}

function iconMore(): FrameNode {
  const f = iconBox();
  for (let i = 0; i < 3; i++) {
    const e = figma.createEllipse();
    e.resize(3, 3);
    e.x = 4 + i * 5;
    e.y = 9;
    e.fills = [{ type: 'SOLID', color: C.text }];
    f.appendChild(e);
  }
  return f;
}

function iconShield(): FrameNode {
  const f = iconBox();
  const r = figma.createRectangle();
  r.resize(12, 14);
  r.x = 4;
  r.y = 3;
  r.cornerRadius = 6;
  r.fills = [{ type: 'SOLID', color: C.success }];
  f.appendChild(r);
  return f;
}

// ============================================================================
// SCREEN BUILDERS
// ============================================================================
const W = 375;
const H = 812;
const STATUS_BAR = 44;
const HOME_INDICATOR = 34;

function statusBar(): FrameNode {
  return frame(
    'status-bar',
    {
      w: 'FILL',
      h: STATUS_BAR,
      pad: [14, 24, 6, 24],
      dir: 'H',
      main: 'SPACE_BETWEEN',
      cross: 'CENTER',
      fill: C.bg,
    },
    txt('9:41', { size: 14, weight: 'Semi Bold' }),
    frame(
      'indicators',
      { dir: 'H', gap: 6, cross: 'CENTER' },
      txt('• • •', { size: 13, color: C.text }),
    ),
  );
}

function screenShell(name: string): FrameNode {
  const f = frame(name, {
    w: W,
    dir: 'V',
    fill: C.bg,
    radius: 28,
    clip: true,
    border: { color: C.borderStrong, width: 1 },
  });
  f.layoutSizingVertical = 'FIXED';
  f.resize(W, H);
  return f;
}

// ----------------------------------------------------------------------------
// 1. LOGIN
// ----------------------------------------------------------------------------
function buildLogin(): FrameNode {
  const screen = screenShell('01 Login');

  const logo = frame(
    'logo',
    { w: 64, h: 64, fill: C.primary, radius: 16, main: 'CENTER', cross: 'CENTER', dir: 'V' },
    txt('A', { size: 32, weight: 'Bold', color: C.textOnPrimary }),
  );

  const title = txt('Arguslog', { size: 32, weight: 'Bold', lineHeight: 38 });
  const tagline = txt("Error tracking that doesn't break the bank.", {
    size: 16,
    color: C.textSub,
    lineHeight: 24,
    align: 'CENTER',
    wrap: true,
  });

  const badgeRow = frame(
    'perks',
    { dir: 'V', gap: 10, cross: 'CENTER' },
    pillBadge('FREE 5K EVENTS / MONTH', C.success, C.successBg),
    pillBadge('EU HOSTED · OPEN SOURCE', C.primary, C.primarySoft),
  );

  const heroBlock = frame(
    'hero',
    { dir: 'V', gap: 16, cross: 'CENTER', pad: [0, 24], w: 'FILL' },
    logo,
    spacer(8),
    title,
    tagline,
    spacer(8),
    badgeRow,
  );

  const ctaBlock = frame(
    'cta',
    { dir: 'V', gap: 12, w: 'FILL', pad: [0, 24] },
    button('Sign in with Keycloak', 'primary'),
    button('Create new organization', 'secondary'),
  );

  const footer = frame(
    'footer',
    { dir: 'V', gap: 4, cross: 'CENTER', w: 'FILL', pad: [0, 24, 24, 24] },
    txt('By signing in you agree to our Terms.', {
      size: 12,
      color: C.textMute,
      align: 'CENTER',
    }),
    txt('Self-hosted? You own your data.', {
      size: 12,
      color: C.textMute,
      align: 'CENTER',
    }),
  );

  const body = frame(
    'body',
    { dir: 'V', w: 'FILL', h: 'FILL', pad: [48, 0, 0, 0], gap: 0, main: 'SPACE_BETWEEN' },
    heroBlock,
    frame('bottom', { dir: 'V', gap: 16, w: 'FILL' }, ctaBlock, footer),
  );

  screen.appendChild(statusBar());
  screen.appendChild(body);
  return screen;
}

// ----------------------------------------------------------------------------
// 2. ONBOARDING — first event in 5 min
// ----------------------------------------------------------------------------
function buildOnboarding(): FrameNode {
  const screen = screenShell('02 Onboarding');

  function tab(label: string, active = false) {
    return frame(
      `tab-${label}`,
      {
        dir: 'H',
        pad: [8, 14],
        radius: R.md,
        fill: active ? C.text : C.surfaceAlt,
        cross: 'CENTER',
      },
      txt(label, { size: 13, weight: 'Semi Bold', color: active ? C.bg : C.textSub }),
    );
  }

  const heroTitle = frame(
    'title',
    { dir: 'V', gap: 6, w: 'FILL' },
    txt('Get your first event', { size: 26, weight: 'Bold', lineHeight: 32 }),
    frame(
      'row',
      { dir: 'H', gap: 8, cross: 'MAX' },
      txt('in', { size: 26, weight: 'Bold', color: C.text }),
      txt('5 minutes', { size: 26, weight: 'Bold', color: C.primary }),
    ),
    spacer(4),
    txt('Pick your stack. Copy. Paste. Done.', { size: 14, color: C.textSub }),
  );

  const tabs = frame(
    'tabs',
    { dir: 'H', gap: 8, w: 'FILL' },
    tab('React', true),
    tab('JS'),
    tab('Java'),
    tab('Spring'),
  );

  const codeLines: { tokens: { text: string; color?: RGB }[] }[] = [
    {
      tokens: [
        { text: 'import', color: C.codeKey },
        { text: ' { ArguslogProvider } ', color: C.codeText },
        { text: 'from', color: C.codeKey },
        { text: " '@arguslog/sdk-react'", color: C.codeStr },
        { text: ';', color: C.codeText },
      ],
    },
    { tokens: [{ text: '', color: C.codeText }] },
    {
      tokens: [
        { text: '<', color: C.codeMute },
        { text: 'ArguslogProvider', color: C.codeFn },
        { text: ' dsn=', color: C.codeText },
        { text: '{import.meta.env.DSN}', color: C.codeStr },
        { text: '>', color: C.codeMute },
      ],
    },
    {
      tokens: [
        { text: '  <', color: C.codeMute },
        { text: 'App', color: C.codeFn },
        { text: ' />', color: C.codeMute },
      ],
    },
    {
      tokens: [
        { text: '</', color: C.codeMute },
        { text: 'ArguslogProvider', color: C.codeFn },
        { text: '>', color: C.codeMute },
      ],
    },
  ];

  function codeLine(line: { tokens: { text: string; color?: RGB }[] }, i: number): FrameNode {
    const lineFrame = frame('line', { dir: 'H', gap: 12, cross: 'CENTER', w: 'FILL' });
    lineFrame.appendChild(
      txt(String(i + 1).padStart(2, ' '), {
        size: 12,
        mono: true,
        color: C.codeMute,
      }),
    );
    const content = txt(line.tokens.map((t) => t.text).join(''), {
      size: 12,
      mono: true,
      color: C.codeText,
      lineHeight: 18,
    });
    let pos = 0;
    for (const tok of line.tokens) {
      if (tok.color && tok.text.length > 0) {
        content.setRangeFills(pos, pos + tok.text.length, [{ type: 'SOLID', color: tok.color }]);
      }
      pos += tok.text.length;
    }
    lineFrame.appendChild(content);
    return lineFrame;
  }

  const codeBody = frame(
    'code-body',
    { dir: 'V', gap: 4, w: 'FILL', pad: [16, 16] },
    ...codeLines.map((l, i) => codeLine(l, i)),
  );

  const codeBlock = frame(
    'code-block',
    {
      dir: 'V',
      w: 'FILL',
      radius: R.lg,
      fill: C.codeBg,
      border: { color: C.codeBorder, width: 1 },
    },
    frame(
      'code-header',
      {
        dir: 'H',
        pad: [10, 16],
        main: 'SPACE_BETWEEN',
        cross: 'CENTER',
        w: 'FILL',
        border: { color: C.codeBorder, width: 1 },
      },
      txt('App.tsx', { size: 12, mono: true, color: C.codeMute }),
      frame(
        'copy',
        { dir: 'H', gap: 6, cross: 'CENTER' },
        iconCopy(),
        txt('Copy', { size: 12, weight: 'Medium', color: C.codeText }),
      ),
    ),
    codeBody,
  );

  const status = frame(
    'live-status',
    {
      dir: 'H',
      gap: 12,
      pad: 16,
      radius: R.lg,
      w: 'FILL',
      fill: C.warnBg,
      cross: 'CENTER',
      border: { color: hex('#FCD34D'), width: 1 },
    },
    dot(10, C.warn),
    frame(
      'lt',
      { dir: 'V', gap: 2, w: 'FILL' },
      txt('Waiting for first event…', { size: 14, weight: 'Semi Bold', color: hex('#92400E') }),
      txt("We'll auto-advance the moment it lands.", {
        size: 12,
        color: hex('#A16207'),
        wrap: true,
      }),
    ),
  );

  const cta = frame(
    'cta',
    { dir: 'V', gap: 12, w: 'FILL' },
    button('Send test event', 'primary'),
    button('View docs', 'secondary'),
  );

  const body = frame(
    'body',
    { dir: 'V', gap: 24, w: 'FILL', pad: [24, 20, 24, 20] },
    heroTitle,
    tabs,
    codeBlock,
    status,
    cta,
  );

  screen.appendChild(statusBar());
  screen.appendChild(topBar('New project', { back: true }));
  screen.appendChild(body);
  return screen;
}

// ----------------------------------------------------------------------------
// 3. ISSUES LIST
// ----------------------------------------------------------------------------
interface IssueRow {
  level: 'error' | 'warn' | 'info';
  title: string;
  culprit: string;
  count: string;
  seen: string;
  env: string;
}

function levelColors(level: 'error' | 'warn' | 'info') {
  if (level === 'error') return { fg: C.error, bg: C.errorBg, label: 'ERROR' };
  if (level === 'warn') return { fg: C.warn, bg: C.warnBg, label: 'WARN' };
  return { fg: C.info, bg: C.infoBg, label: 'INFO' };
}

function issueCard(row: IssueRow): FrameNode {
  const lc = levelColors(row.level);
  const left = frame('level-rail', { w: 4, h: 'FILL', fill: lc.fg, radius: 2 });
  const meta = frame(
    'meta',
    { dir: 'H', gap: 8, cross: 'CENTER' },
    pillBadge(lc.label, lc.fg, lc.bg),
    txt(row.env, { size: 11, weight: 'Medium', color: C.textMute, letterSpacing: 0.4 }),
  );
  const title = txt(row.title, {
    size: 15,
    weight: 'Semi Bold',
    lineHeight: 22,
    color: C.text,
    wrap: true,
  });
  const culprit = txt(row.culprit, { size: 12, mono: true, color: C.textSub });
  const stats = frame(
    'stats',
    { dir: 'H', gap: 12, cross: 'CENTER', w: 'FILL', main: 'SPACE_BETWEEN' },
    frame(
      'left',
      { dir: 'H', gap: 8, cross: 'CENTER' },
      txt(row.count, { size: 12, weight: 'Semi Bold', color: C.text }),
      txt('events', { size: 12, color: C.textSub }),
    ),
    txt(row.seen, { size: 12, color: C.textMute }),
  );

  const content = frame(
    'card-content',
    { dir: 'V', gap: 8, w: 'FILL', pad: [14, 16] },
    meta,
    title,
    culprit,
    spacer(2),
    stats,
  );

  const card = frame(
    'issue-card',
    {
      dir: 'H',
      w: 'FILL',
      fill: C.bg,
      radius: R.lg,
      border: { color: C.border, width: 1 },
      clip: true,
    },
    left,
    content,
  );
  return card;
}

function buildIssuesList(): FrameNode {
  const screen = screenShell('03 Issues');

  const projectSwitcher = frame(
    'switcher',
    {
      dir: 'H',
      gap: 8,
      pad: [6, 12],
      radius: R.pill,
      fill: C.surfaceAlt,
      cross: 'CENTER',
    },
    dot(8, C.success),
    txt('web-dashboard', { size: 13, weight: 'Semi Bold' }),
    txt('prod', { size: 11, weight: 'Medium', color: C.textMute, letterSpacing: 0.4 }),
  );

  const right = [iconSearch(), iconFilter()];
  const bar = frame(
    'top-bar',
    {
      dir: 'H',
      pad: [14, 20],
      w: 'FILL',
      fill: C.bg,
      cross: 'CENTER',
      main: 'SPACE_BETWEEN',
      border: { color: C.border, width: 1 },
    },
    projectSwitcher,
    frame('right', { dir: 'H', gap: 16, cross: 'CENTER' }, ...right),
  );

  const segmented = frame(
    'segmented',
    { dir: 'H', w: 'FILL', fill: C.surfaceAlt, radius: R.md, pad: 4, gap: 4 },
    frame(
      'seg-active',
      { dir: 'H', w: 'FILL', main: 'CENTER', pad: [8, 0], fill: C.bg, radius: R.sm, shadow: 'sm' },
      txt('Unresolved', { size: 13, weight: 'Semi Bold' }),
    ),
    frame(
      'seg',
      { dir: 'H', w: 'FILL', main: 'CENTER', pad: [8, 0] },
      txt('All', { size: 13, weight: 'Medium', color: C.textSub }),
    ),
    frame(
      'seg',
      { dir: 'H', w: 'FILL', main: 'CENTER', pad: [8, 0] },
      txt('Mine', { size: 13, weight: 'Medium', color: C.textSub }),
    ),
  );

  const chips = frame(
    'chips',
    { dir: 'H', gap: 8, w: 'FILL' },
    chip('Error', true),
    chip('Warning'),
    chip('Info'),
    chip('Last 24h'),
  );

  const issues: IssueRow[] = [
    {
      level: 'error',
      title: "TypeError: Cannot read properties of undefined (reading 'id')",
      culprit: 'checkout/CartItem.tsx:42',
      count: '2,347',
      seen: '2 min ago',
      env: 'PROD',
    },
    {
      level: 'error',
      title: 'NullPointerException at OrderService.process',
      culprit: 'OrderService.java:118',
      count: '186',
      seen: '12 min ago',
      env: 'PROD',
    },
    {
      level: 'warn',
      title: 'Slow query > 2s in /api/v1/issues',
      culprit: 'JdbcIssueRepository.list',
      count: '44',
      seen: '1 h ago',
      env: 'STAGING',
    },
    {
      level: 'info',
      title: 'Sourcemap not found for v1.4.2 (chunk-vendor)',
      culprit: 'symbolicator',
      count: '9',
      seen: '3 h ago',
      env: 'PROD',
    },
  ];

  const list = frame('issues-list', { dir: 'V', gap: 10, w: 'FILL' }, ...issues.map(issueCard));

  const body = frame(
    'body',
    { dir: 'V', gap: 16, w: 'FILL', pad: [16, 16, 24, 16], h: 'FILL' },
    segmented,
    chips,
    list,
  );

  screen.appendChild(statusBar());
  screen.appendChild(bar);
  screen.appendChild(body);
  screen.appendChild(bottomTabBar('issues'));
  return screen;
}

// ----------------------------------------------------------------------------
// 4. ISSUE DETAIL
// ----------------------------------------------------------------------------
function buildIssueDetail(): FrameNode {
  const screen = screenShell('04 Issue detail');

  const tb = topBar('Issue', { back: true, right: [iconShare(), iconMore()] });

  const header = frame(
    'header',
    { dir: 'V', gap: 10, pad: [16, 20, 16, 20], w: 'FILL', fill: C.bg },
    frame(
      'row1',
      { dir: 'H', gap: 8, cross: 'CENTER' },
      pillBadge('ERROR', C.error, C.errorBg),
      pillBadge('PROD', C.text, C.surfaceAlt),
      pillBadge('v1.4.2', C.primary, C.primarySoft),
    ),
    txt("TypeError: Cannot read properties of undefined (reading 'id')", {
      size: 18,
      weight: 'Semi Bold',
      lineHeight: 26,
      wrap: true,
    }),
    txt('checkout/CartItem.tsx:42 — handleRemove()', {
      size: 13,
      mono: true,
      color: C.textSub,
    }),
  );

  function statCell(top: string, bottom: string, accent?: RGB) {
    return frame(
      'stat',
      { dir: 'V', gap: 4, w: 'FILL', pad: [12, 0] },
      txt(top, {
        size: 18,
        weight: 'Bold',
        color: accent ?? C.text,
      }),
      txt(bottom, { size: 12, color: C.textSub }),
    );
  }

  const stats = frame(
    'stats',
    {
      dir: 'H',
      w: 'FILL',
      fill: C.surface,
      radius: R.lg,
      border: { color: C.border, width: 1 },
      pad: [4, 16],
    },
    statCell('2,347', 'events'),
    frame('sep', { w: 1, h: 'FILL', fill: C.border }),
    statCell('142', 'users'),
    frame('sep', { w: 1, h: 'FILL', fill: C.border }),
    statCell('2 min', 'last seen'),
  );

  const sparkline = frame(
    'spark',
    {
      dir: 'V',
      w: 'FILL',
      pad: [16, 16],
      gap: 8,
      radius: R.lg,
      fill: C.bg,
      border: { color: C.border, width: 1 },
    },
    frame(
      'spark-head',
      { dir: 'H', main: 'SPACE_BETWEEN', cross: 'CENTER', w: 'FILL' },
      txt('Last 24 hours', { size: 12, weight: 'Medium', color: C.textSub }),
      txt('peak: 412 / 5m', { size: 11, mono: true, color: C.textMute }),
    ),
    sparklineBars(),
  );

  const stTabs = frame(
    'st-tabs',
    { dir: 'H', gap: 16, w: 'FILL', pad: [0, 0, 0, 0] },
    frame(
      't',
      {
        dir: 'V',
        pad: [0, 0, 8, 0],
        gap: 0,
        border: { color: C.primary, width: 2 },
      },
      txt('Stack', { size: 14, weight: 'Semi Bold', color: C.primary }),
    ),
    txt('Events', { size: 14, weight: 'Medium', color: C.textSub }),
    txt('Tags', { size: 14, weight: 'Medium', color: C.textSub }),
  );

  // simulate a stack trace with one highlighted frame
  const stackLines = [
    { file: 'checkout/CartItem.tsx', line: '42', fn: 'handleRemove', active: true },
    { file: 'checkout/Cart.tsx', line: '108', fn: 'renderItem' },
    { file: 'node_modules/react-dom', line: '—', fn: 'commitRoot' },
  ];

  const stackBlock = frame(
    'stack',
    {
      dir: 'V',
      w: 'FILL',
      radius: R.lg,
      fill: C.codeBg,
      clip: true,
    },
    ...stackLines.map((s) => stackFrame(s.file, s.line, s.fn, !!s.active)),
  );

  const actions = frame(
    'actions',
    { dir: 'H', gap: 8, w: 'FILL' },
    button('Resolve', 'primary', true),
    button('Mute', 'secondary', true),
  );

  const body = frame(
    'body',
    { dir: 'V', gap: 16, w: 'FILL', pad: [0, 16, 24, 16] },
    header,
    stats,
    sparkline,
    stTabs,
    stackBlock,
    actions,
  );

  screen.appendChild(statusBar());
  screen.appendChild(tb);
  screen.appendChild(body);
  return screen;
}

function stackFrame(file: string, line: string, fn: string, active: boolean): FrameNode {
  return frame(
    'stack-frame',
    {
      dir: 'V',
      gap: 4,
      pad: [12, 16],
      w: 'FILL',
      fill: active ? hex('#1F2937') : C.codeBg,
      border: { color: active ? C.error : C.codeBorder, width: active ? 2 : 1 },
    },
    frame(
      'row',
      { dir: 'H', main: 'SPACE_BETWEEN', w: 'FILL', cross: 'CENTER' },
      txt(fn + '()', {
        size: 13,
        mono: true,
        color: active ? C.codeFn : C.codeText,
        weight: 'Medium',
      }),
      txt('L' + line, { size: 11, mono: true, color: C.codeMute }),
    ),
    txt(file, { size: 11, mono: true, color: C.codeMute }),
  );
}

function sparklineBars(): FrameNode {
  const heights = [
    12, 18, 14, 22, 30, 25, 36, 48, 40, 52, 64, 58, 46, 38, 30, 26, 22, 16, 24, 32, 40, 56, 72, 60,
  ];
  const bars = heights.map((h, i) => {
    const r = figma.createRectangle();
    r.resize(8, h);
    r.cornerRadius = 2;
    r.fills = [{ type: 'SOLID', color: i === heights.length - 2 ? C.error : C.primary }];
    r.name = 'bar';
    return r;
  });
  return frame('bars', { dir: 'H', gap: 4, w: 'FILL', h: 80, cross: 'MAX' }, ...bars);
}

// ----------------------------------------------------------------------------
// 5. RELEASES
// ----------------------------------------------------------------------------
function buildReleases(): FrameNode {
  const screen = screenShell('05 Releases');

  function releaseCard(opts: {
    version: string;
    sha: string;
    when: string;
    status: 'stable' | 'regression' | 'deploying';
    newIssues: number;
    fixed: number;
  }): FrameNode {
    const statusMap = {
      stable: { fg: C.success, bg: C.successBg, label: 'STABLE' },
      regression: { fg: C.error, bg: C.errorBg, label: 'REGRESSION' },
      deploying: { fg: C.info, bg: C.infoBg, label: 'DEPLOYING' },
    };
    const sm = statusMap[opts.status];
    return frame(
      'release',
      {
        dir: 'V',
        gap: 12,
        pad: 16,
        w: 'FILL',
        fill: C.bg,
        border: { color: C.border, width: 1 },
        radius: R.lg,
      },
      frame(
        'row1',
        { dir: 'H', main: 'SPACE_BETWEEN', w: 'FILL', cross: 'CENTER' },
        frame(
          'left',
          { dir: 'V', gap: 4 },
          txt(opts.version, { size: 16, weight: 'Bold' }),
          txt(opts.sha + ' · ' + opts.when, { size: 11, mono: true, color: C.textMute }),
        ),
        pillBadge(sm.label, sm.fg, sm.bg),
      ),
      divider(),
      frame(
        'metrics',
        { dir: 'H', w: 'FILL', main: 'SPACE_BETWEEN', cross: 'CENTER' },
        frame(
          'm',
          { dir: 'V', gap: 2 },
          txt('+' + opts.newIssues, {
            size: 18,
            weight: 'Bold',
            color: opts.newIssues > 0 ? C.error : C.text,
          }),
          txt('new issues', { size: 11, color: C.textSub }),
        ),
        frame(
          'm',
          { dir: 'V', gap: 2 },
          txt('-' + opts.fixed, {
            size: 18,
            weight: 'Bold',
            color: opts.fixed > 0 ? C.success : C.text,
          }),
          txt('fixed', { size: 11, color: C.textSub }),
        ),
        frame(
          'm',
          { dir: 'V', gap: 2, cross: 'MAX' },
          txt('→', { size: 18, color: C.textMute }),
          txt('details', { size: 11, color: C.textSub }),
        ),
      ),
    );
  }

  const list = frame(
    'list',
    { dir: 'V', gap: 12, w: 'FILL' },
    releaseCard({
      version: 'v1.4.2',
      sha: '320ad6c',
      when: '2 h ago',
      status: 'regression',
      newIssues: 3,
      fixed: 0,
    }),
    releaseCard({
      version: 'v1.4.1',
      sha: '3909bf4',
      when: '2 d ago',
      status: 'stable',
      newIssues: 0,
      fixed: 2,
    }),
    releaseCard({
      version: 'v1.4.0',
      sha: '38aaae2',
      when: '5 d ago',
      status: 'stable',
      newIssues: 1,
      fixed: 4,
    }),
    releaseCard({
      version: 'v1.3.0',
      sha: 'c4ad17a',
      when: '12 d ago',
      status: 'stable',
      newIssues: 0,
      fixed: 7,
    }),
  );

  const summary = frame(
    'summary',
    {
      dir: 'V',
      gap: 6,
      pad: 16,
      w: 'FILL',
      fill: C.primarySoft,
      radius: R.lg,
    },
    txt('Latest: v1.4.2', { size: 13, weight: 'Semi Bold', color: C.primary }),
    txt('Regression alert — 3 new issues since v1.4.1.', {
      size: 13,
      color: C.text,
      lineHeight: 18,
      wrap: true,
    }),
  );

  const body = frame(
    'body',
    { dir: 'V', gap: 16, w: 'FILL', pad: [16, 16, 24, 16], h: 'FILL' },
    summary,
    list,
  );

  screen.appendChild(statusBar());
  screen.appendChild(topBar('Releases'));
  screen.appendChild(body);
  screen.appendChild(bottomTabBar('releases'));
  return screen;
}

// ----------------------------------------------------------------------------
// 6. ALERTS
// ----------------------------------------------------------------------------
function buildAlerts(): FrameNode {
  const screen = screenShell('06 Alerts');

  function destination(label: string, sub: string, on: boolean, mark: string): FrameNode {
    const toggleTrack = frame(
      'track',
      {
        w: 44,
        h: 26,
        radius: 13,
        fill: on ? C.primary : C.borderStrong,
        dir: 'H',
        cross: 'CENTER',
        pad: [3, 3],
        main: on ? 'MAX' : 'MIN',
      },
      frame('knob', { w: 20, h: 20, radius: R.pill, fill: C.bg, shadow: 'sm' }),
    );
    const initials = frame(
      'logo',
      {
        w: 36,
        h: 36,
        radius: R.md,
        fill: on ? C.primarySoft : C.surfaceAlt,
        cross: 'CENTER',
        main: 'CENTER',
        dir: 'V',
      },
      txt(mark, { size: 14, weight: 'Bold', color: on ? C.primary : C.textSub }),
    );
    return frame(
      'dest',
      {
        dir: 'H',
        gap: 12,
        pad: 14,
        w: 'FILL',
        fill: C.bg,
        radius: R.lg,
        border: { color: C.border, width: 1 },
        cross: 'CENTER',
      },
      initials,
      frame(
        'labels',
        { dir: 'V', gap: 2, w: 'FILL' },
        txt(label, { size: 14, weight: 'Semi Bold' }),
        txt(sub, { size: 12, color: C.textSub }),
      ),
      toggleTrack,
    );
  }

  const dests = frame(
    'destinations',
    { dir: 'V', gap: 10, w: 'FILL' },
    destination('Slack', '#alerts-prod · 2 rules', true, 'S'),
    destination('Discord', 'Connect your server', false, 'D'),
    destination('Email', 'ops@arguslog.org', true, '@'),
    destination('Webhook', 'Custom HTTP endpoint', false, '{'),
  );

  function ruleCard(opts: {
    title: string;
    condition: string;
    action: string;
    on: boolean;
  }): FrameNode {
    return frame(
      'rule',
      {
        dir: 'V',
        gap: 10,
        pad: 14,
        w: 'FILL',
        fill: C.bg,
        radius: R.lg,
        border: { color: C.border, width: 1 },
      },
      frame(
        'head',
        { dir: 'H', main: 'SPACE_BETWEEN', cross: 'CENTER', w: 'FILL' },
        txt(opts.title, { size: 14, weight: 'Semi Bold' }),
        pillBadge(
          opts.on ? 'ENABLED' : 'PAUSED',
          opts.on ? C.success : C.textMute,
          opts.on ? C.successBg : C.surfaceAlt,
        ),
      ),
      frame(
        'row',
        { dir: 'H', gap: 6, cross: 'CENTER' },
        txt('WHEN', { size: 10, weight: 'Bold', color: C.textMute, letterSpacing: 0.6 }),
        txt(opts.condition, { size: 12, mono: true, color: C.text }),
      ),
      frame(
        'row',
        { dir: 'H', gap: 6, cross: 'CENTER' },
        txt('THEN', { size: 10, weight: 'Bold', color: C.textMute, letterSpacing: 0.6 }),
        txt(opts.action, { size: 12, mono: true, color: C.text }),
      ),
    );
  }

  const rules = frame(
    'rules',
    { dir: 'V', gap: 10, w: 'FILL' },
    ruleCard({
      title: 'Prod errors → Slack',
      condition: 'level=error AND env=prod',
      action: 'post to #alerts-prod',
      on: true,
    }),
    ruleCard({
      title: 'Regression → page on-call',
      condition: 'release.regression=true',
      action: 'email + Slack DM',
      on: true,
    }),
    ruleCard({
      title: 'Quota at 80%',
      condition: 'events_used > 80%',
      action: 'email billing@',
      on: false,
    }),
  );

  const sectionTitle = (s: string) =>
    txt(s, { size: 11, weight: 'Bold', color: C.textMute, letterSpacing: 0.8 });

  const body = frame(
    'body',
    { dir: 'V', gap: 12, w: 'FILL', pad: [16, 16, 24, 16], h: 'FILL' },
    sectionTitle('DESTINATIONS'),
    dests,
    button('+ Add destination', 'secondary'),
    spacer(8),
    sectionTitle('RULES'),
    rules,
  );

  screen.appendChild(statusBar());
  screen.appendChild(topBar('Alerts'));
  screen.appendChild(body);
  screen.appendChild(bottomTabBar('alerts'));
  return screen;
}

// ----------------------------------------------------------------------------
// 7. PLAN & BILLING
// ----------------------------------------------------------------------------
function buildBilling(): FrameNode {
  const screen = screenShell('07 Plan & Billing');

  const planCard = frame(
    'plan',
    {
      dir: 'V',
      gap: 16,
      pad: 20,
      w: 'FILL',
      fill: C.text,
      radius: R.xl,
    },
    frame(
      'row',
      { dir: 'H', main: 'SPACE_BETWEEN', w: 'FILL', cross: 'CENTER' },
      frame(
        'l',
        { dir: 'V', gap: 4 },
        txt('PRO', { size: 11, weight: 'Bold', color: hex('#A5B4FC'), letterSpacing: 1.2 }),
        frame(
          'price',
          { dir: 'H', gap: 4, cross: 'MAX' },
          txt('$9', { size: 36, weight: 'Bold', color: C.bg }),
          txt('/ month', { size: 13, color: hex('#9CA3AF') }),
        ),
      ),
      pillBadge('CURRENT', hex('#86EFAC'), hex('#064E3B')),
    ),
    txt('100,000 events / month — predictable, no overage charges.', {
      size: 13,
      color: hex('#D1D5DB'),
      lineHeight: 20,
      wrap: true,
    }),
  );

  const usage = frame(
    'usage',
    {
      dir: 'V',
      gap: 12,
      pad: 16,
      w: 'FILL',
      fill: C.bg,
      radius: R.lg,
      border: { color: C.border, width: 1 },
    },
    frame(
      'head',
      { dir: 'H', main: 'SPACE_BETWEEN', w: 'FILL', cross: 'MAX' },
      txt('Usage this period', { size: 13, weight: 'Semi Bold' }),
      txt('23,456 / 100,000', { size: 12, mono: true, color: C.textSub }),
    ),
    progressBar(0.234),
    frame(
      'foot',
      { dir: 'H', main: 'SPACE_BETWEEN', w: 'FILL' },
      txt('Resets May 31', { size: 11, color: C.textMute }),
      txt('23%', { size: 11, weight: 'Semi Bold', color: C.success }),
    ),
  );

  const callout = frame(
    'callout',
    {
      dir: 'H',
      gap: 12,
      pad: 14,
      w: 'FILL',
      fill: C.successBg,
      radius: R.lg,
      border: { color: hex('#A7F3D0'), width: 1 },
      cross: 'MIN',
    },
    iconShield(),
    frame(
      'txt',
      { dir: 'V', gap: 4, w: 'FILL' },
      txt('No surprise bills.', { size: 13, weight: 'Semi Bold', color: hex('#065F46') }),
      txt('We pause new events at the cap and notify you. We never silently charge overage.', {
        size: 12,
        color: hex('#047857'),
        lineHeight: 18,
        wrap: true,
      }),
    ),
  );

  function invoiceRow(opts: {
    date: string;
    plan: string;
    total: string;
    status: 'paid' | 'pending';
  }): FrameNode {
    const statusMap = {
      paid: { fg: C.success, bg: C.successBg, label: 'PAID' },
      pending: { fg: C.warn, bg: C.warnBg, label: 'PENDING' },
    };
    const sm = statusMap[opts.status];
    return frame(
      'invoice',
      {
        dir: 'H',
        gap: 12,
        pad: [12, 14],
        w: 'FILL',
        fill: C.bg,
        cross: 'CENTER',
        main: 'SPACE_BETWEEN',
      },
      frame(
        'l',
        { dir: 'V', gap: 2, w: 'FILL' },
        txt(opts.date, { size: 13, weight: 'Semi Bold' }),
        txt(opts.plan, { size: 11, color: C.textSub }),
      ),
      frame(
        'r',
        { dir: 'H', gap: 12, cross: 'CENTER' },
        pillBadge(sm.label, sm.fg, sm.bg),
        txt(opts.total, { size: 13, weight: 'Semi Bold' }),
      ),
    );
  }

  const invoices = frame(
    'invoices',
    {
      dir: 'V',
      w: 'FILL',
      fill: C.bg,
      border: { color: C.border, width: 1 },
      radius: R.lg,
      clip: true,
    },
    invoiceRow({ date: 'Apr 30, 2026', plan: 'Pro · 100k', total: '$9.00', status: 'paid' }),
    divider(),
    invoiceRow({ date: 'Mar 31, 2026', plan: 'Pro · 100k', total: '$9.00', status: 'paid' }),
    divider(),
    invoiceRow({ date: 'Feb 28, 2026', plan: 'Pro · 100k', total: '$9.00', status: 'paid' }),
  );

  const sectionTitle = (s: string) =>
    txt(s, { size: 11, weight: 'Bold', color: C.textMute, letterSpacing: 0.8 });

  const body = frame(
    'body',
    { dir: 'V', gap: 16, w: 'FILL', pad: [16, 16, 24, 16], h: 'FILL' },
    planCard,
    usage,
    callout,
    button('Manage subscription', 'secondary'),
    spacer(4),
    sectionTitle('RECENT INVOICES'),
    invoices,
  );

  screen.appendChild(statusBar());
  screen.appendChild(topBar('Plan & Billing', { back: true }));
  screen.appendChild(body);
  return screen;
}

function progressBar(pct: number): FrameNode {
  const fillW = Math.max(0, Math.min(1, pct));
  const fillRect = figma.createRectangle();
  // 311 = 375 - (2 * 16 outer padding) - (2 * 16 card padding). Card width = 343, content = 311.
  fillRect.resize(Math.max(2, Math.round(311 * fillW)), 8);
  fillRect.cornerRadius = R.pill;
  fillRect.fills = [{ type: 'SOLID', color: C.primary }];
  fillRect.name = 'fill';
  return frame(
    'track',
    {
      w: 'FILL',
      h: 8,
      radius: R.pill,
      fill: C.surfaceAlt,
      clip: true,
    },
    fillRect,
  );
}

// ============================================================================
// DESIGN TOKENS PREVIEW (visible cheat sheet)
// ============================================================================
function buildTokens(): FrameNode {
  function swatch(name: string, hexStr: string, color: RGB, dark = false): FrameNode {
    return frame(
      'swatch',
      {
        dir: 'V',
        gap: 8,
        w: 110,
        pad: [12, 12],
        fill: C.bg,
        radius: R.md,
        border: { color: C.border, width: 1 },
      },
      frame('chip', {
        w: 86,
        h: 56,
        radius: R.sm,
        fill: color,
        border: dark ? undefined : { color: C.border, width: 1 },
      }),
      txt(name, { size: 12, weight: 'Semi Bold' }),
      txt(hexStr, { size: 11, mono: true, color: C.textSub }),
    );
  }

  const palette = [
    ['primary', '#4F46E5', C.primary, true],
    ['primaryDark', '#4338CA', C.primaryDark, true],
    ['accent', '#7C3AED', C.accent, true],
    ['text', '#111827', C.text, true],
    ['textSub', '#6B7280', C.textSub, true],
    ['textMute', '#9CA3AF', C.textMute, true],
    ['surface', '#F9FAFB', C.surface, false],
    ['border', '#E5E7EB', C.border, false],
    ['error', '#DC2626', C.error, true],
    ['warn', '#D97706', C.warn, true],
    ['success', '#059669', C.success, true],
    ['info', '#2563EB', C.info, true],
  ] as const;

  const swatchesGrid = frame(
    'swatches',
    { dir: 'H', gap: 12, w: 'FILL' },
    ...palette.slice(0, 6).map((p) => swatch(p[0], p[1], p[2], p[3])),
  );
  const swatchesGrid2 = frame(
    'swatches2',
    { dir: 'H', gap: 12, w: 'FILL' },
    ...palette.slice(6).map((p) => swatch(p[0], p[1], p[2], p[3])),
  );

  function typeRow(label: string, sample: string, opts: TOpts): FrameNode {
    return frame(
      'type-row',
      {
        dir: 'H',
        gap: 16,
        w: 'FILL',
        cross: 'CENTER',
        pad: [12, 16],
        border: { color: C.border, width: 1 },
        radius: R.md,
        fill: C.bg,
      },
      frame(
        'meta',
        { dir: 'V', gap: 2, w: 120 },
        txt(label, { size: 12, weight: 'Semi Bold' }),
        txt(
          opts.size + '/' + (opts.lineHeight ?? opts.size! + 4) + ' ' + (opts.weight ?? 'Regular'),
          {
            size: 10,
            mono: true,
            color: C.textMute,
          },
        ),
      ),
      txt(sample, opts),
    );
  }

  const typeBlock = frame(
    'typography',
    { dir: 'V', gap: 8, w: 'FILL' },
    typeRow('H1 / Display', 'Errors, demystified.', { size: 28, weight: 'Bold', lineHeight: 34 }),
    typeRow('H2 / Title', 'Issues this week', { size: 22, weight: 'Semi Bold', lineHeight: 28 }),
    typeRow('H3 / Section', 'Recent events', { size: 18, weight: 'Semi Bold', lineHeight: 24 }),
    typeRow('Body / Default', 'TanStack Query owns server state.', { size: 14, lineHeight: 20 }),
    typeRow('Caption', 'Last seen 2 minutes ago', {
      size: 12,
      color: C.textSub,
      lineHeight: 16,
    }),
    typeRow('Mono / Code', 'checkout/CartItem.tsx:42', { size: 13, mono: true }),
  );

  const tokensFrame = frame(
    'Design Tokens',
    {
      dir: 'V',
      w: 1280,
      pad: 48,
      gap: 32,
      fill: C.surface,
      radius: 24,
      border: { color: C.border, width: 1 },
    },
    frame(
      'head',
      { dir: 'V', gap: 8 },
      txt('Arguslog Mobile · Design Tokens', { size: 32, weight: 'Bold' }),
      txt(
        'Mobile-first. Friendly without being childish. Built for developers (precision, code) and POs (clarity, outcomes).',
        { size: 15, color: C.textSub, lineHeight: 22 },
      ),
    ),
    frame(
      'section',
      { dir: 'V', gap: 16, w: 'FILL' },
      txt('Color', { size: 18, weight: 'Semi Bold' }),
      swatchesGrid,
      swatchesGrid2,
    ),
    frame(
      'section',
      { dir: 'V', gap: 16, w: 'FILL' },
      txt('Typography (Inter + JetBrains Mono)', { size: 18, weight: 'Semi Bold' }),
      typeBlock,
    ),
    frame(
      'section',
      { dir: 'V', gap: 16, w: 'FILL' },
      txt('Spacing scale (px)', { size: 18, weight: 'Semi Bold' }),
      frame(
        'spacing',
        { dir: 'H', gap: 12, w: 'FILL', cross: 'MIN' },
        ...[4, 8, 12, 16, 20, 24, 32, 48].map((n) =>
          frame(
            'sp',
            { dir: 'V', gap: 6, cross: 'CENTER' },
            frame('box', { w: n, h: 32, fill: C.primary, radius: 4 }),
            txt(String(n), { size: 11, mono: true, color: C.textSub }),
          ),
        ),
      ),
    ),
  );

  return tokensFrame;
}

// ============================================================================
// MAIN
// ============================================================================
async function main() {
  try {
    await loadFonts();

    const tokens = buildTokens();
    const screens = [
      buildLogin(),
      buildOnboarding(),
      buildIssuesList(),
      buildIssueDetail(),
      buildReleases(),
      buildAlerts(),
      buildBilling(),
    ];

    // Lay out: tokens row at the top, screens row below
    tokens.x = 0;
    tokens.y = 0;
    figma.currentPage.appendChild(tokens);

    let x = 0;
    const y = tokens.y + tokens.height + 80;
    for (const s of screens) {
      figma.currentPage.appendChild(s);
      s.x = x;
      s.y = y;
      x += W + 64;
    }

    // Frame everything in a "section" for tidy navigation
    figma.viewport.scrollAndZoomIntoView([tokens, ...screens]);
    figma.notify('Arguslog mobile design generated — 7 screens + tokens.');
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    figma.notify('Plugin error: ' + msg, { error: true });
    console.error(e);
  } finally {
    figma.closePlugin();
  }
}

main();
