import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

interface Item {
  id: number;
  name: string;
  description: string;
}

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  title = 'demo-frontend';
  items: Item[] = [];
  newName = '';
  rawHtmlPreview = '';

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  lastError: any;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.loadItems();
  }

  loadItems(): void {
    this.http.get<Item[]>('/api/items').subscribe({
      next: (data) => (this.items = data),
      error: (err) => {
        // Sonar: swallowing the error without real handling
        this.lastError = err;
      }
    });
  }

  addItem(): void {
    this.http.post<Item>('/api/items', { name: this.newName }).subscribe(() => {
      this.newName = '';
      this.loadItems();
    });
  }

  // Sonar: binding unsanitized user input as HTML is an XSS-prone pattern
  updatePreview(value: string): void {
    this.rawHtmlPreview = value;
  }
}
