import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig).catch((err) => {
  console.error(err);
  const boot = document.querySelector('.boot');
  if (boot) boot.innerHTML = '<span class="boot-badge">NMS</span><p>The server could not be reached.</p><p><a href="">Try again</a></p>';
});
