import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { ChartData, ChartSpec, DatasetInfo, QueryRequest, QueryResult, Widget } from '@shared/types';

export interface Me {
  username: string;
  name: string;
  tenants: string[];
  canWrite: boolean;
}

export interface Tenant {
  id: string;
  name: string;
  environment: string;
  specie: string;
  color: string;
  farms: number;
}

/** A farm on the tenant page — open issues by severity as `critical` … `low`. */
export interface FarmRow {
  id: string;
  name: string;
  farmName: string;
  country: string;
  address: string;
  connectivityStatus: string;
  version: string;
  protocol: string;
  animals: number;
  devices: number;
  offlineDevices: number;
  lowBatteryDevices: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
}

export interface Farm {
  id: string;
  name: string;
  farmName: string;
  specie: string;
  environment: string;
  connectivityStatus: string;
  version: string;
  protocol: string;
  contractUser: string;
  animals: number;
  address: string;
  country: string;
  latitude: number;
  longitude: number;
  registeredAt: string;
  lastUpgradeAt: string | null;
  tenantId: string;
  tenantName: string;
  tenantColor: string;
}

export interface Device {
  id: string;
  type: string;
  hardware: string;
  firmware: string;
  status: string;
  battery: number;
  lastSeen: string;
  openIssues: number;
}

export interface Issue {
  id: string;
  description: string;
  category: string;
  severity: string;
  state: string;
  createdAt: string;
  resolvedAt: string | null;
  deviceId: string;
  deviceType: string | null;
}

export interface FarmDetail {
  farm: Farm;
  devices: Device[];
  issues: Issue[];
  connectivity: { day: string; online: number; offline: number; total: number }[];
}

/** The Java backend's REST API. Same origin in production; proxied to :4300 by `ng serve`. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  readonly #http = inject(HttpClient);

  me(): Promise<Me> {
    return this.#call(this.#http.get<Me>('/api/me'));
  }

  datasets(): Promise<DatasetInfo[]> {
    return this.#call(this.#http.get<DatasetInfo[]>('/api/datasets'));
  }

  query(request: QueryRequest): Promise<QueryResult> {
    return this.#call(this.#http.post<QueryResult>('/api/query', request));
  }

  tenants(): Promise<Tenant[]> {
    return this.#call(this.#http.get<Tenant[]>('/api/tenants'));
  }

  tenant(id: string): Promise<{ tenant: Tenant; farms: FarmRow[] }> {
    return this.#call(this.#http.get<{ tenant: Tenant; farms: FarmRow[] }>(`/api/tenants/${encodeURIComponent(id)}`));
  }

  farm(id: string): Promise<FarmDetail> {
    return this.#call(this.#http.get<FarmDetail>(`/api/farms/${encodeURIComponent(id)}`));
  }

  chart(spec: ChartSpec): Promise<ChartData> {
    return this.#call(this.#http.post<ChartData>('/api/chart', spec));
  }

  widgets(): Promise<Widget[]> {
    return this.#call(this.#http.get<Widget[]>('/api/widgets'));
  }

  pin(spec: ChartSpec, cols?: number, rows?: number): Promise<Widget> {
    return this.#call(this.#http.post<Widget>('/api/widgets', { spec, cols, rows }));
  }

  resetLayout(): Promise<Widget[]> {
    return this.#call(this.#http.post<Widget[]>('/api/widgets/reset', {}));
  }

  remove(id: string): Promise<{ ok: boolean }> {
    return this.#call(this.#http.delete<{ ok: boolean }>(`/api/widgets/${encodeURIComponent(id)}`));
  }

  panelHtml(): Promise<string> {
    return this.#call(this.#http.get('/panel.html', { responseType: 'text' }));
  }

  /** The backend answers errors as {"error": "..."}; surface that message. */
  async #call<T>(request: import('rxjs').Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(request);
    } catch (error) {
      if (error instanceof HttpErrorResponse) throw new Error(error.error?.error ?? error.message);
      throw error;
    }
  }
}
