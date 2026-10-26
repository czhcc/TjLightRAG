import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import App from './App';
import i18n from './i18n';

beforeEach(async () => {
  localStorage.clear(); vi.restoreAllMocks(); await i18n.changeLanguage('zh');
});

it('keeps protected navigation unavailable without a session', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ok:false,status:401})));
  render(<App />);
  await screen.findByLabelText('用户名');
  const nav = within(screen.getByRole('navigation'));
  expect(nav.queryByRole('button', {name:'注销'})).toBeNull();
  expect(screen.queryByRole('main', {name:'Workspace'})).toBeNull();
});

it('restores the session and keeps bilingual account navigation', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ok:true,status:200})));
  render(<App />);
  await screen.findByRole('main', {name:'Workspace'});
  const nav = within(screen.getByRole('navigation'));
  expect(nav.getByRole('button', {name:'注销'})).toBeTruthy();
  fireEvent.click(nav.getByRole('button', {name:'English'}));
  expect(nav.getByRole('button', {name:'Sign out'})).toBeTruthy();
  expect(localStorage.getItem('language')).toBe('en');
});

it('preserves workspace navigation when logout fails', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url:string) => ({ok:!url.endsWith('/logout'),status:url.endsWith('/logout')?500:200,json:async()=>({token:'fixed-test-csrf'})})));
  render(<App />);
  fireEvent.click(await screen.findByRole('button', {name:'注销'}));
  await screen.findByRole('alert');
  expect(screen.getByRole('main', {name:'Workspace'})).toBeTruthy();
  expect(screen.getByRole('button', {name:'注销'})).toBeTruthy();
});
