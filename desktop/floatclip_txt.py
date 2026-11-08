from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
import tkinter as tk
from tkinter import messagebox, ttk

APP_NAME = "FloatClip TXT"
COLORS = {
    "navy": "#172033", "blue": "#2563EB", "blue_hover": "#1D4ED8",
    "background": "#F5F7FB", "surface": "#FFFFFF", "text": "#172033",
    "muted": "#64748B", "line": "#D8E0EB", "danger": "#DC2626",
}


@dataclass(frozen=True)
class QuickTextItem:
    id: int
    title: str
    content: str
    position: int


def default_database_path() -> Path:
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA", str(Path.home())))
    else:
        base = Path.home() / ".local" / "share"
    folder = base / "FloatClipTXT"
    folder.mkdir(parents=True, exist_ok=True)
    return folder / "floatclip_txt.db"


class QuickTextStore:
    """SQLite storage and sequential queue logic for the TXT-only desktop app."""

    def __init__(self, database_path: str | Path):
        self.database_path = Path(database_path)
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    @contextmanager
    def _connection(self):
        connection = sqlite3.connect(str(self.database_path), timeout=10)
        connection.row_factory = sqlite3.Row
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.execute("""
                CREATE TABLE IF NOT EXISTS quick_texts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    position INTEGER NOT NULL
                )
            """)
            connection.execute("""
                CREATE TABLE IF NOT EXISTS app_settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)

    @staticmethod
    def _item(row: sqlite3.Row) -> QuickTextItem:
        return QuickTextItem(
            id=int(row["id"]), title=str(row["title"]),
            content=str(row["content"]), position=int(row["position"]),
        )

    def list_items(self) -> list[QuickTextItem]:
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT id, title, content, position FROM quick_texts "
                "ORDER BY position ASC, id ASC"
            ).fetchall()
        return [self._item(row) for row in rows]

    def add(self, title: str, content: str) -> int:
        return self.insert_many([content], position=None, title=title)[0]

    def insert_many(
        self, contents: list[str], position: int | None = None, title: str = "Text"
    ) -> list[int]:
        values = [value for value in contents if value.strip()]
        if not values:
            return []
        with self._connection() as connection:
            if position is None:
                row = connection.execute(
                    "SELECT COALESCE(MAX(position), -1) + 1 AS next_position "
                    "FROM quick_texts"
                ).fetchone()
                insert_at = int(row["next_position"])
            else:
                row = connection.execute(
                    "SELECT COUNT(*) AS item_count FROM quick_texts"
                ).fetchone()
                insert_at = max(0, min(int(position), int(row["item_count"])))
            connection.execute(
                "UPDATE quick_texts SET position = position + ? WHERE position >= ?",
                (len(values), insert_at),
            )
            result = []
            for offset, value in enumerate(values):
                cursor = connection.execute(
                    "INSERT INTO quick_texts (title, content, position) VALUES (?, ?, ?)",
                    (title, value, insert_at + offset),
                )
                result.append(int(cursor.lastrowid))
            return result

    def update(self, item_id: int, title: str, content: str) -> None:
        if not content.strip():
            raise ValueError("Text content cannot be empty.")
        with self._connection() as connection:
            cursor = connection.execute(
                "UPDATE quick_texts SET title = ?, content = ? WHERE id = ?",
                (title.strip() or "Text", content, int(item_id)),
            )
            if cursor.rowcount == 0:
                raise KeyError(f"Quick Text row {item_id} was not found.")

    def delete(self, item_id: int) -> None:
        self.delete_many([item_id])

    def delete_many(self, item_ids: list[int]) -> None:
        ids = list(dict.fromkeys(int(value) for value in item_ids))
        if not ids:
            return
        placeholders = ",".join("?" for _ in ids)
        with self._connection() as connection:
            connection.execute(
                f"DELETE FROM quick_texts WHERE id IN ({placeholders})", ids
            )
            remaining = connection.execute(
                "SELECT id FROM quick_texts ORDER BY position ASC, id ASC"
            ).fetchall()
            for position, row in enumerate(remaining):
                connection.execute(
                    "UPDATE quick_texts SET position = ? WHERE id = ?",
                    (position, int(row["id"])),
                )

    def reorder(self, ordered_ids: list[int]) -> None:
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT id FROM quick_texts ORDER BY position ASC, id ASC"
            ).fetchall()
            existing = [int(row["id"]) for row in rows]
            existing_set = set(existing)
            ordered, seen = [], set()
            for raw_id in ordered_ids:
                item_id = int(raw_id)
                if item_id in existing_set and item_id not in seen:
                    ordered.append(item_id)
                    seen.add(item_id)
            ordered.extend(item_id for item_id in existing if item_id not in seen)
            for position, item_id in enumerate(ordered):
                connection.execute(
                    "UPDATE quick_texts SET position = ? WHERE id = ?",
                    (position, item_id),
                )

    def last_copied_id(self) -> int | None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT value FROM app_settings WHERE key = 'last_copied_item_id'"
            ).fetchone()
        if row is None:
            return None
        try:
            return int(row["value"])
        except (TypeError, ValueError):
            return None

    def mark_copied(self, item_id: int) -> None:
        with self._connection() as connection:
            connection.execute("""
                INSERT INTO app_settings (key, value)
                VALUES ('last_copied_item_id', ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """, (str(int(item_id)),))

    def next_item(self) -> QuickTextItem | None:
        items = self.list_items()
        if not items:
            return None
        last_id = self.last_copied_id()
        last_index = next(
            (i for i, item in enumerate(items) if item.id == last_id), -1
        )
        return items[(last_index + 1) % len(items)]


class FloatClipTXTApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.store = QuickTextStore(default_database_path())
        self.items_by_id: dict[int, QuickTextItem] = {}
        self._drag_ids: list[int] = []
        self._cell_editor: tk.Entry | None = None
        self._editing_id: int | None = None
        self.status_var = tk.StringVar(
            value="Ready. Paste multiple lines to add many rows at once."
        )
        self.count_var = tk.StringVar(value="0 rows")
        self.next_var = tk.StringVar(value="NEXT UP  ·  ROW 1")
        self.buttons: dict[str, tk.Button] = {}

        self.root.title(APP_NAME)
        self.root.geometry("1120x760")
        self.root.minsize(850, 590)
        self.root.configure(bg=COLORS["background"])
        self.root.option_add("*Font", ("Segoe UI", 10))
        self._configure_styles()
        self._build_interface()
        self.refresh()
        self.root.bind("<Control-n>", lambda _event: self.add_text())
        self.root.bind("<Control-Return>", lambda _event: self.copy_next())
        self.root.bind("<Delete>", lambda _event: self.delete_selected())
        self.root.protocol("WM_DELETE_WINDOW", self.close)

    def _configure_styles(self) -> None:
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure(
            "Sheet.Treeview", background="#FFFFFF", fieldbackground="#FFFFFF",
            foreground=COLORS["text"], rowheight=38, borderwidth=0,
            font=("Segoe UI", 10),
        )
        style.map(
            "Sheet.Treeview", background=[("selected", "#DBEAFE")],
            foreground=[("selected", COLORS["navy"])],
        )
        style.configure(
            "Sheet.Treeview.Heading", background="#EAF0F8",
            foreground="#334155", relief="flat", borderwidth=1,
            font=("Segoe UI", 9, "bold"), padding=(10, 10),
        )
        style.map("Sheet.Treeview.Heading", background=[("active", "#DCE6F3")])
        style.configure(
            "Sheet.Vertical.TScrollbar", background="#CBD5E1",
            troughcolor="#F1F5F9", bordercolor="#F1F5F9",
            arrowcolor="#64748B",
        )

    def _button(
        self, parent: tk.Widget, label: str, command, *,
        background: str = "#FFFFFF", foreground: str = COLORS["text"],
        active_background: str | None = None, padx: int = 12,
        pady: int = 9, font_size: int = 9,
    ) -> tk.Button:
        return tk.Button(
            parent, text=label, command=command, bg=background, fg=foreground,
            activebackground=active_background or background,
            activeforeground=foreground, relief="flat", bd=0, padx=padx,
            pady=pady, cursor="hand2", font=("Segoe UI", font_size, "bold"),
            highlightthickness=1 if background == "#FFFFFF" else 0,
            highlightbackground=COLORS["line"],
            disabledforeground="#94A3B8",
        )

    def _build_interface(self) -> None:
        page = tk.Frame(self.root, bg=COLORS["background"])
        page.pack(fill="both", expand=True)
        header = tk.Frame(page, bg=COLORS["navy"], padx=26, pady=20)
        header.pack(fill="x")
        title_area = tk.Frame(header, bg=COLORS["navy"])
        title_area.pack(side="left", fill="x", expand=True)
        tk.Label(
            title_area, text="FloatClip TXT", bg=COLORS["navy"],
            fg="#FFFFFF", font=("Segoe UI", 21, "bold"),
        ).pack(anchor="w")
        tk.Label(
            title_area, text="Spreadsheet-style quick text queue",
            bg=COLORS["navy"], fg="#CBD5E1", font=("Segoe UI", 10),
        ).pack(anchor="w", pady=(3, 0))
        tk.Label(
            header, text="TXT ONLY", bg="#26354F", fg="#BFDBFE",
            font=("Segoe UI", 9, "bold"), padx=12, pady=7,
        ).pack(side="right", anchor="center")

        content = tk.Frame(page, bg=COLORS["background"], padx=22, pady=17)
        content.pack(fill="both", expand=True)
        toolbar = tk.Frame(content, bg=COLORS["background"])
        toolbar.pack(fill="x", pady=(0, 12))
        left = tk.Frame(toolbar, bg=COLORS["background"])
        left.pack(side="left", fill="x", expand=True)

        self.buttons["add"] = self._button(
            left, "+ Add Row", self.add_text, background=COLORS["blue"],
            foreground="#FFFFFF", active_background=COLORS["blue_hover"], padx=14,
        )
        self.buttons["add"].pack(side="left", padx=(0, 6))
        self.buttons["paste"] = self._button(
            left, "▤ Paste Rows", self.paste_rows, background="#DBEAFE",
            foreground="#1D4ED8", active_background="#BFDBFE", padx=14,
        )
        self.buttons["paste"].pack(side="left", padx=4)
        self.buttons["delete"] = self._button(
            left, "Delete Rows", self.delete_selected, background="#FEF2F2",
            foreground=COLORS["danger"], active_background="#FEE2E2",
        )
        self.buttons["delete"].pack(side="left", padx=4)
        self.buttons["up"] = self._button(
            left, "↑", lambda: self.move_selected(-1), padx=12
        )
        self.buttons["up"].pack(side="left", padx=4)
        self.buttons["down"] = self._button(
            left, "↓", lambda: self.move_selected(1), padx=12
        )
        self.buttons["down"].pack(side="left", padx=4)
        self.buttons["copy_selected"] = self._button(
            left, "Copy Selected", self.copy_selected
        )
        self.buttons["copy_selected"].pack(side="left", padx=4)
        self.buttons["copy_next"] = self._button(
            toolbar, "COPY NEXT  →", self.copy_next, background=COLORS["blue"],
            foreground="#FFFFFF", active_background=COLORS["blue_hover"],
            padx=20, pady=12, font_size=10,
        )
        self.buttons["copy_next"].pack(side="right", padx=(10, 0))

        info = tk.Frame(content, bg=COLORS["background"])
        info.pack(fill="x", pady=(0, 8))
        tk.Label(
            info, text="QUICK TEXT SHEET", bg=COLORS["background"],
            fg=COLORS["navy"], font=("Segoe UI", 11, "bold"),
        ).pack(side="left")
        tk.Label(
            info, textvariable=self.count_var, bg=COLORS["background"],
            fg=COLORS["muted"], font=("Segoe UI", 9),
        ).pack(side="left", padx=(11, 0))
        tk.Label(
            info,
            text="Select multiple rows with Ctrl / Shift  ·  Double-click a cell to edit",
            bg=COLORS["background"], fg=COLORS["muted"], font=("Segoe UI", 9),
        ).pack(side="right")

        table_card = tk.Frame(
            content, bg="#FFFFFF", highlightthickness=1,
            highlightbackground=COLORS["line"],
        )
        table_card.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(
            table_card, columns=("row", "cell"), show="headings",
            selectmode="extended", style="Sheet.Treeview",
        )
        self.tree.heading("row", text="#", anchor="center")
        self.tree.heading("cell", text="A", anchor="w")
        self.tree.column("row", width=62, minwidth=62, stretch=False, anchor="center")
        self.tree.column("cell", width=900, minwidth=350, stretch=True, anchor="w")
        self.tree.tag_configure("even", background="#FFFFFF")
        self.tree.tag_configure("odd", background="#F8FAFC")
        scrollbar = ttk.Scrollbar(
            table_card, orient="vertical", command=self.tree.yview,
            style="Sheet.Vertical.TScrollbar",
        )
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side="left", fill="both", expand=True, padx=(7, 0), pady=7)
        scrollbar.pack(side="right", fill="y", padx=(0, 7), pady=7)

        self.tree.bind("<<TreeviewSelect>>", self._on_selection_changed)
        self.tree.bind("<Double-1>", self._on_double_click)
        self.tree.bind("<ButtonPress-1>", self._on_drag_start, add="+")
        self.tree.bind("<ButtonRelease-1>", self._on_drag_drop, add="+")
        self.tree.bind("<Control-v>", self._on_paste_shortcut)
        self.tree.bind("<Control-V>", self._on_paste_shortcut)
        self.tree.bind("<Control-c>", self._on_copy_shortcut)
        self.tree.bind("<Control-C>", self._on_copy_shortcut)
        self.tree.bind("<Control-a>", self._on_select_all)
        self.tree.bind("<Control-A>", self._on_select_all)
        self.tree.bind("<F2>", self._on_edit_shortcut)

        footer = tk.Frame(content, bg=COLORS["background"], pady=11)
        footer.pack(fill="x")
        tk.Label(
            footer, textvariable=self.status_var, bg=COLORS["background"],
            fg=COLORS["muted"], font=("Segoe UI", 9),
        ).pack(side="left")
        tk.Label(
            footer, textvariable=self.next_var, bg=COLORS["background"],
            fg=COLORS["blue"], font=("Segoe UI", 9, "bold"),
        ).pack(side="right")
        tk.Label(
            content,
            text="TIP  Paste multiple lines from any app or spreadsheet. Each line becomes a new row. "
                 "Selected rows? New rows are inserted after the last selected row.",
            bg=COLORS["background"], fg="#64748B", font=("Segoe UI", 8),
            anchor="w", justify="left",
        ).pack(fill="x", pady=(2, 0))

    def refresh(self, selected_ids: list[int] | None = None) -> None:
        items = self.store.list_items()
        self.items_by_id = {item.id: item for item in items}
        for child in self.tree.get_children():
            self.tree.delete(child)
        for index, item in enumerate(items):
            display = item.content.replace("\r\n", "\n").replace("\r", "\n")
            display = display.replace("\n", "  ↵  ").replace("\t", "    ")
            self.tree.insert(
                "", "end", iid=str(item.id), values=(index + 1, display),
                tags=("even" if index % 2 == 0 else "odd",),
            )
        self.count_var.set(f"{len(items)} row{'s' if len(items) != 1 else ''}")
        next_item = self.store.next_item()
        next_index = next(
            (i + 1 for i, entry in enumerate(items)
             if next_item and entry.id == next_item.id), 1,
        )
        self.next_var.set(
            f"NEXT UP  ·  ROW {next_index}" if next_item else "NEXT UP  ·  —"
        )
        valid = [
            str(item_id) for item_id in (selected_ids or [])
            if str(item_id) in self.tree.get_children()
        ]
        if valid:
            self.tree.selection_set(*valid)
            self.tree.focus(valid[0])
            self.tree.see(valid[0])
        self._update_button_states()

    def _selected_ids(self) -> list[int]:
        selected = set(self.tree.selection())
        return [
            int(item_id) for item_id in self.tree.get_children()
            if item_id in selected
        ]

    def _update_button_states(self) -> None:
        selected = self._selected_ids()
        has_selection = bool(selected)
        for key in ("delete", "copy_selected", "up", "down"):
            self.buttons[key].configure(state="normal" if has_selection else "disabled")
        self.buttons["copy_next"].configure(
            state="normal" if self.items_by_id else "disabled"
        )
        if not selected:
            return
        ordered = [int(value) for value in self.tree.get_children()]
        selected_set = set(selected)
        indexes = [ordered.index(item_id) for item_id in selected]
        can_up = any(
            i > 0 and ordered[i - 1] not in selected_set for i in indexes
        )
        can_down = any(
            i < len(ordered) - 1 and ordered[i + 1] not in selected_set
            for i in indexes
        )
        self.buttons["up"].configure(state="normal" if can_up else "disabled")
        self.buttons["down"].configure(state="normal" if can_down else "disabled")

    def _on_selection_changed(self, _event=None) -> None:
        self._update_button_states()

    def _on_double_click(self, event) -> None:
        if self.tree.identify_region(event.x, event.y) != "cell":
            return
        if self.tree.identify_column(event.x) != "#2":
            return
        row_id = self.tree.identify_row(event.y)
        if not row_id:
            return
        self.tree.selection_add(row_id)
        item = self.items_by_id.get(int(row_id))
        if item is None:
            return
        if "\n" in item.content or "\r" in item.content:
            self._open_text_editor(item)
        else:
            self._start_inline_edit(item, row_id)

    def _start_inline_edit(self, item: QuickTextItem, row_id: str) -> None:
        bbox = self.tree.bbox(row_id, "cell")
        if not bbox:
            return
        x, y, width, height = bbox
        editor = tk.Entry(
            self.tree, font=("Segoe UI", 10), relief="solid", bd=1,
            bg="#FFFFFF", fg=COLORS["text"], insertbackground=COLORS["text"],
            highlightthickness=1, highlightbackground=COLORS["blue"],
        )
        editor.insert(0, item.content)
        editor.select_range(0, "end")
        editor.place(x=x, y=y, width=width, height=height)
        self._cell_editor, self._editing_id = editor, item.id

        def finish(save: bool) -> None:
            if self._cell_editor is not editor:
                return
            self._cell_editor = None
            self._editing_id = None
            value = editor.get()
            editor.destroy()
            if save and value.strip():
                try:
                    self.store.update(item.id, item.title, value)
                except Exception as error:
                    messagebox.showerror(APP_NAME, str(error), parent=self.root)
                self.refresh(selected_ids=[item.id])
                self.status_var.set(f"Updated row {item.position + 1}.")
            elif save:
                self.refresh(selected_ids=[item.id])
                self.status_var.set("A row cannot be empty. Previous value kept.")
            else:
                self.refresh(selected_ids=[item.id])

        editor.bind("<Return>", lambda _event: (finish(True), "break")[1])
        editor.bind("<Escape>", lambda _event: (finish(False), "break")[1])
        editor.bind("<FocusOut>", lambda _event: finish(True))
        editor.focus_set()

    @staticmethod
    def _save_button_label(item: QuickTextItem | None) -> str:
        return "Add Row" if item is None else "Save Cell"

    def _open_text_editor(self, item: QuickTextItem | None = None) -> None:
        dialog = tk.Toplevel(self.root)
        dialog.title("Edit Cell" if item else "Add Row")
        dialog.configure(bg=COLORS["background"])
        dialog.resizable(True, True)
        dialog.transient(self.root)
        dialog.grab_set()
        dialog.geometry("650x400")
        container = tk.Frame(dialog, bg=COLORS["background"], padx=22, pady=20)
        container.pack(fill="both", expand=True)
        tk.Label(
            container, text="Edit Cell" if item else "Add Text Row",
            bg=COLORS["background"], fg=COLORS["navy"],
            font=("Segoe UI", 16, "bold"),
        ).pack(anchor="w")
        tk.Label(
            container, text="Enter the text stored in this spreadsheet cell.",
            bg=COLORS["background"], fg=COLORS["muted"], font=("Segoe UI", 9),
        ).pack(anchor="w", pady=(4, 12))
        text_frame = tk.Frame(
            container, bg="#FFFFFF", highlightthickness=1,
            highlightbackground=COLORS["line"],
        )
        text_frame.pack(fill="both", expand=True, pady=(0, 14))
        text_box = tk.Text(
            text_frame, wrap="word", undo=True, font=("Segoe UI", 10),
            relief="flat", bd=0, padx=10, pady=10,
            bg="#FFFFFF", fg=COLORS["text"], insertbackground=COLORS["text"],
        )
        text_box.pack(fill="both", expand=True)
        if item:
            text_box.insert("1.0", item.content)
        buttons = tk.Frame(container, bg=COLORS["background"])
        buttons.pack(fill="x")

        def save() -> None:
            value = text_box.get("1.0", "end-1c")
            if not value.strip():
                messagebox.showwarning(
                    APP_NAME, "Enter some text before saving.", parent=dialog
                )
                text_box.focus_set()
                return
            try:
                if item is None:
                    selected_id = self.store.insert_many([value])[0]
                    message = "New row added."
                else:
                    self.store.update(item.id, item.title, value)
                    selected_id, message = item.id, "Cell updated."
            except Exception as error:
                messagebox.showerror(APP_NAME, str(error), parent=dialog)
                return
            dialog.destroy()
            self.refresh(selected_ids=[selected_id])
            self.status_var.set(message)

        tk.Button(
            buttons, text="Cancel", command=dialog.destroy, bg="#FFFFFF",
            fg=COLORS["text"], activebackground="#F1F5F9", relief="flat",
            bd=0, padx=18, pady=10, cursor="hand2",
            font=("Segoe UI", 9, "bold"),
        ).pack(side="right", padx=(8, 0))
        tk.Button(
            buttons, text=self._save_button_label(item), command=save, bg=COLORS["blue"],
            fg="#FFFFFF", activebackground=COLORS["blue_hover"],
            activeforeground="#FFFFFF", relief="flat", bd=0, padx=20,
            pady=10, cursor="hand2", font=("Segoe UI", 9, "bold"),
        ).pack(side="right")
        dialog.bind("<Control-Return>", lambda _event: save())
        dialog.bind("<Escape>", lambda _event: dialog.destroy())
        text_box.focus_set()

    def add_text(self) -> None:
        self._open_text_editor()

    def paste_rows(self) -> None:
        try:
            raw = self.root.clipboard_get()
        except tk.TclError:
            messagebox.showwarning(
                APP_NAME, "Clipboard is empty or does not contain plain text.",
                parent=self.root,
            )
            return
        normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        lines = normalized.split("\n")
        while lines and not lines[-1].strip():
            lines.pop()
        values = [line for line in lines if line.strip()]
        if not values:
            messagebox.showwarning(
                APP_NAME, "No non-empty text rows were found.", parent=self.root
            )
            return
        ordered = [int(value) for value in self.tree.get_children()]
        selected = self._selected_ids()
        if selected:
            insert_at = max(ordered.index(item_id) for item_id in selected) + 1
        else:
            insert_at = len(ordered)
        try:
            inserted = self.store.insert_many(values, position=insert_at)
        except Exception as error:
            messagebox.showerror(APP_NAME, str(error), parent=self.root)
            return
        self.refresh(selected_ids=inserted)
        self.status_var.set(
            f"Added {len(inserted)} row{'s' if len(inserted) != 1 else ''} from clipboard."
        )

    def _on_paste_shortcut(self, _event=None):
        self.paste_rows()
        return "break"

    def _on_copy_shortcut(self, _event=None):
        self.copy_selected()
        return "break"

    def _on_select_all(self, _event=None):
        children = self.tree.get_children()
        if children:
            self.tree.selection_set(*children)
            self._update_button_states()
        return "break"

    def _on_edit_shortcut(self, _event=None):
        selected = self._selected_ids()
        if len(selected) == 1:
            item = self.items_by_id.get(selected[0])
            if item:
                self._open_text_editor(item)
        return "break"

    def delete_selected(self) -> None:
        selected = self._selected_ids()
        if not selected:
            return
        prompt = (
            "Delete the selected row?" if len(selected) == 1
            else f"Delete all {len(selected)} selected rows?"
        )
        if not messagebox.askyesno(APP_NAME, prompt, parent=self.root):
            return
        ordered = [int(value) for value in self.tree.get_children()]
        first_index = min(ordered.index(item_id) for item_id in selected)
        self.store.delete_many(selected)
        remaining = self.store.list_items()
        selected_id = (
            remaining[min(first_index, len(remaining) - 1)].id if remaining else None
        )
        self.refresh(selected_ids=[selected_id] if selected_id else [])
        self.status_var.set(
            f"Deleted {len(selected)} row{'s' if len(selected) != 1 else ''}."
        )

    def move_selected(self, direction: int) -> None:
        selected = self._selected_ids()
        if not selected:
            return
        ordered = [int(value) for value in self.tree.get_children()]
        selected_set = set(selected)
        if direction < 0:
            for index in range(1, len(ordered)):
                if ordered[index] in selected_set and ordered[index - 1] not in selected_set:
                    ordered[index - 1], ordered[index] = ordered[index], ordered[index - 1]
        else:
            for index in range(len(ordered) - 2, -1, -1):
                if ordered[index] in selected_set and ordered[index + 1] not in selected_set:
                    ordered[index], ordered[index + 1] = ordered[index + 1], ordered[index]
        self.store.reorder(ordered)
        self.refresh(selected_ids=selected)
        self.status_var.set("Selected rows moved.")

    def _on_drag_start(self, event) -> None:
        if self.tree.identify_region(event.x, event.y) not in ("cell", "tree"):
            self._drag_ids = []
            return
        row_id = self.tree.identify_row(event.y)
        if not row_id:
            self._drag_ids = []
            return
        if row_id not in self.tree.selection():
            self.tree.selection_set(row_id)
        self._drag_ids = self._selected_ids()

    def _on_drag_drop(self, event) -> None:
        dragged = list(self._drag_ids)
        self._drag_ids = []
        if not dragged:
            return
        target = self.tree.identify_row(event.y)
        ordered = [int(value) for value in self.tree.get_children()]
        dragged_set = set(dragged)
        if target:
            target_id = int(target)
            if target_id in dragged_set:
                return
            bbox = self.tree.bbox(target, "cell")
            after = bool(bbox and event.y >= bbox[1] + bbox[3] / 2)
            ordered = [item_id for item_id in ordered if item_id not in dragged_set]
            insert_at = ordered.index(target_id) + (1 if after else 0)
        else:
            ordered = [item_id for item_id in ordered if item_id not in dragged_set]
            insert_at = len(ordered)
        ordered[insert_at:insert_at] = [
            item_id for item_id in dragged if item_id in self.items_by_id
        ]
        self.store.reorder(ordered)
        self.refresh(selected_ids=dragged)
        self.status_var.set("Row order updated.")

    def copy_selected(self) -> None:
        selected = self._selected_ids()
        if not selected:
            return
        combined = "\n".join(self.items_by_id[item_id].content for item_id in selected)
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append(combined)
            self.root.update()
        except tk.TclError as error:
            messagebox.showerror(
                APP_NAME, f"Could not copy text to the Windows clipboard.\n\n{error}",
                parent=self.root,
            )
            return
        self.status_var.set(
            f"Copied {len(selected)} selected row{'s' if len(selected) != 1 else ''}."
        )

    def copy_next(self) -> None:
        item = self.store.next_item()
        if item is None:
            self.status_var.set("Sheet is empty. Add a row or paste multiple lines.")
            return
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append(item.content)
            self.root.update()
        except tk.TclError as error:
            messagebox.showerror(
                APP_NAME, f"Could not copy text to the Windows clipboard.\n\n{error}",
                parent=self.root,
            )
            return
        self.store.mark_copied(item.id)
        items = self.store.list_items()
        index = next((i for i, entry in enumerate(items) if entry.id == item.id), 0)
        next_item = self.store.next_item()
        next_index = next(
            (i + 1 for i, entry in enumerate(items)
             if next_item and entry.id == next_item.id), 1,
        )
        self.next_var.set(
            f"NEXT UP  ·  ROW {next_index}" if next_item else "NEXT UP  ·  —"
        )
        self.status_var.set(f"Copied row {index + 1} of {len(items)}.")

    def close(self) -> None:
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    FloatClipTXTApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
