import {Component} from '@angular/core';
import {RouterLink} from '@angular/router';

/** Public entry. The synthetic pilot and private-account workspace never share credentials. */
@Component({
  selector: 'carbonflow-landing',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './landing.html',
})
export class LandingComponent {}
