import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { switchMap } from 'rxjs';
import { HttpClient } from '@angular/common/http';

interface CsrfResponse {
  token: string;
  headerName: string;
}

/** Obtain a fresh, session-bound token before every write, including authentication writes. */
export const csrfInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || !['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method)) {
    return next(request);
  }

  const http = inject(HttpClient);
  return http.get<CsrfResponse>('/api/auth/csrf', { withCredentials: true }).pipe(
    switchMap(({ headerName, token }) => next(request.clone({
      withCredentials: true,
      setHeaders: { [headerName]: token }
    })))
  );
};
