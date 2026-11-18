import {Component, input, signal} from '@angular/core';
import {IsActiveMatchOptions, RouterLink, RouterLinkActive} from '@angular/router';

@Component({
  selector: 'aside[cfWorkspaceNavigation]',
  imports: [RouterLink, RouterLinkActive],
  host: {class: 'cf-workspace-sidebar cf-workspace-navigation', 'aria-label': 'Navegação do CarbonFlow'},
  templateUrl: './workspace-navigation.html',
})
export class WorkspaceNavigationComponent {
  readonly projectId = input<string | null>(null);
  readonly owner = input(true);
  readonly open = signal(false);
  readonly activeOptions: IsActiveMatchOptions = {
    paths: 'exact', queryParams: 'ignored', matrixParams: 'ignored', fragment: 'ignored',
  };

  closeOnEscape(event: Event, toggle: HTMLButtonElement) {
    if (!this.open()) return;
    event.preventDefault();
    this.open.set(false);
    toggle.focus();
  }
}
