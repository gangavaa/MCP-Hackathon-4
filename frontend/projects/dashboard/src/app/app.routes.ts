import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./dashboard/estate-dashboard-page').then((m) => m.EstateDashboardPage), title: 'NMS · Dashboard' },
  { path: 'tenants/:tenantId', loadComponent: () => import('./dashboard/tenant-page').then((m) => m.TenantPage), title: 'NMS · Tenant' },
  { path: 'farms/:farmId', loadComponent: () => import('./dashboard/farm-page').then((m) => m.FarmPage), title: 'NMS · Farm' },
  { path: 'host', loadComponent: () => import('./host/host-page').then((m) => m.HostPage), title: 'NMS · Panel test host' },
  { path: '**', redirectTo: '' },
];
