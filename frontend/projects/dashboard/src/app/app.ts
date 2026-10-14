import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ProgressBar } from './core/loading';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ProgressBar],
  template: '<nms-progress-bar /><router-outlet />',
})
export class App {}
