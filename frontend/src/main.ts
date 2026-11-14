import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { MessageService } from 'primeng/api';
import { AppComponent } from './app/app.component';
import { csrfInterceptor } from './app/csrf.interceptor';

bootstrapApplication(AppComponent, {
  providers: [
    provideHttpClient(withInterceptors([csrfInterceptor])),
    provideAnimations(),
    MessageService
  ]
}).catch(error => console.error(error));
