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
    "navy": "#172033",
    "blue": "#2563EB",
    "blue_hover": "#1D4ED8",
    "background": "#F5F7FB",
    "surface": "#FFFFFF",
    "text": "#172033",
    "muted": "#64748B",
    "line": "#E2E8F0",
    "soft_blue": "#EFF6FF",
    "danger": "#DC2626",
    "danger_hover": "#B91C1C",
    "green": "#15803D",
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
    """SQLite storage and queue-order logic for the TXT-only desktop app."""

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
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS quick_texts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    position INTEGER NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS app_settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
            )

    @staticmethod
    def _item(row: sqlite3.Row) -> QuickTextItem:
        return QuickTextItem(
            id=int(row["id"]),
            title=str(row["title"]),
            content=str(row["content"]),
            position=int(row["position"]),
        )

    def list_items(self) -> list[QuickTextItem]:
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT id, title, content, position "
                "FROM quick_texts ORDER BY position ASC, id ASC"
            ).fetchall()
        return [self._item(row) for row in rows]

    def add(self, title: str, content: str) -> int:
        clean_title = title.strip() or "Text"
        if not content.strip():
            raise ValueError("Text content cannot be empty.")
        with self._connection() as connection:
            row = connection.execute(
                "SELECT COALESCE(MAX(position), -1) + 1 AS next_position "
                "FROM quick_texts"
            ).fetchone()
            cursor = connection.execute(
                "INSERT INTO quick_texts (title, content, position) VALUES (?, ?, ?)",
                (clean_title, content, int(row["next_position"])),
            )
            return int(cursor.lastrowid)

    def update(self, item_id: int, title: str, content: str) -> None:
        clean_title = title.strip() or "Text"
        if not content.strip():
            raise ValueError("Text content cannot be empty.")
        with self._connection() as connection:
            cursor = connection.execute(
                "UPDATE quick_texts SET title = ?, content = ? WHERE id = ?",
                (clean_title, content, int(item_id)),
            )
            if cursor.rowcount == 0:
                raise KeyError(f"Quick Text item {item_id} was not found.")

    def delete(self, item_id: int) -> None:
        with self._connection() as connection:
            connection.execute("DELETE FROM quick_texts WHERE id = ?", (int(item_id),))
            remaining = connection.execute(
                "SELECT id FROM quick_texts ORDER BY position ASC, id ASC"
            ).fetchall()
            for position, row in enumerate(remaining):
                connection.execute(
                    "UPDATE quick_texts SET position = ? WHERE id = ?",
                    (position, int(row["id"])),
                )
        # If the deleted item was the last copied item, its ID is deliberately
        # left in settings. The next lookup will not find it and will restart
        # from the first item, matching the Android TXT queue behavior.

    def reorder(self, ordered_ids: list[int]) -> None:
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT id FROM quick_texts ORDER BY position ASC, id ASC"
            ).fetchall()
            existing = [int(row["id"]) for row in rows]
            existing_set = set(existing)
            ordered: list[int] = []
            seen: set[int] = set()
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
            connection.execute(
                """
                INSERT INTO app_settings (key, value)
                VALUES ('last_copied_item_id', ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """,
                (str(int(item_id)),),
            )

    def next_item(self) -> QuickTextItem | None:
        items = self.list_items()
        if not items:
            return None
        last_id = self.last_copied_id()
        last_index = next(
            (index for index, item in enumerate(items) if item.id == last_id),
            -1,
        )
        return items[(last_index + 1) % len(items)]


class FloatClipTXTApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.store = QuickTextStore(default_database_path())
        self.items_by_id: dict[int, QuickTextItem] = {}
        self._drag_item_id: int | None = None
        self.status_var = tk.StringVar(value="Ready. Add your first text to build the queue.")
        self.count_var = tk.StringVar(value="0 saved texts")
        self.next_var = tk.StringVar(value="NEXT UP  ·  —")
        self.buttons: dict[str, tk.Button] = {}

        self.root.title(APP_NAME)
        self.root.geometry("1080x720")
        self.root.minsize(850, 580)
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
            "Queue.Treeview",
            background=COLORS["surface"],
            fieldbackground=COLORS["surface"],
            foreground=COLORS["text"],
            rowheight=42,
            borderwidth=0,
            font=("Segoe UI", 10),
        )
        style.map(
            "Queue.Treeview",
            background=[("selected", "#DBEAFE")],
            foreground=[("selected", COLORS["navy"])],
        )
        style.configure(
            "Queue.Treeview.Heading",
            background="#EEF2F7",
            foreground="#475569",
            relief="flat",
            borderwidth=0,
            font=("Segoe UI", 9, "bold"),
            padding=(10, 11),
        )
        style.map(
            "Queue.Treeview.Heading",
            background=[("active", "#E2E8F0")],
        )
        style.configure(
            "Queue.Vertical.TScrollbar",
            background="#CBD5E1",
            troughcolor="#F1F5F9",
            bordercolor="#F1F5F9",
            arrowcolor="#64748B",
        )

    def _button(
        self,
        parent: tk.Widget,
        label: str,
        command,
        *,
        background: str = "#FFFFFF",
        foreground: str = COLORS["text"],
        active_background: str | None = None,
        padx: int = 14,
        pady: int = 10,
        font_size: int = 9,
    ) -> tk.Button:
        button = tk.Button(
            parent,
            text=label,
            command=command,
            bg=background,
            fg=foreground,
            activebackground=active_background or background,
            activeforeground=foreground,
            relief="flat",
            bd=0,
            padx=padx,
            pady=pady,
            cursor="hand2",
            font=("Segoe UI", font_size, "bold"),
            highlightthickness=1 if background == "#FFFFFF" else 0,
            highlightbackground=COLORS["line"],
            disabledforeground="#94A3B8",
        )
        return button

    def _build_interface(self) -> None:
        page = tk.Frame(self.root, bg=COLORS["background"])
        page.pack(fill="both", expand=True)

        header = tk.Frame(page, bg=COLORS["navy"], padx=26, pady=22)
        header.pack(fill="x")
        title_area = tk.Frame(header, bg=COLORS["navy"])
        title_area.pack(side="left", fill="x", expand=True)
        tk.Label(
            title_area,
            text="FloatClip TXT",
            bg=COLORS["navy"],
            fg="#FFFFFF",
            font=("Segoe UI", 22, "bold"),
        ).pack(anchor="w")
        tk.Label(
            title_area,
            text="Your sequential text clipboard queue",
            bg=COLORS["navy"],
            fg="#CBD5E1",
            font=("Segoe UI", 10),
        ).pack(anchor="w", pady=(3, 0))
        tk.Label(
            header,
            text="TXT ONLY",
            bg="#26354F",
            fg="#BFDBFE",
            font=("Segoe UI", 9, "bold"),
            padx=12,
            pady=7,
        ).pack(side="right", anchor="center")

        content = tk.Frame(page, bg=COLORS["background"], padx=22, pady=18)
        content.pack(fill="both", expand=True)

        toolbar = tk.Frame(content, bg=COLORS["background"])
        toolbar.pack(fill="x", pady=(0, 15))

        left_actions = tk.Frame(toolbar, bg=COLORS["background"])
        left_actions.pack(side="left", fill="x", expand=True)

        self.buttons["add"] = self._button(
            left_actions, "+ Add Text", self.add_text,
            background=COLORS["blue"], foreground="#FFFFFF",
            active_background=COLORS["blue_hover"], padx=16,
        )
        self.buttons["add"].pack(side="left", padx=(0, 7))
        self.buttons["edit"] = self._button(left_actions, "Edit", self.edit_selected)
        self.buttons["edit"].pack(side="left", padx=4)
        self.buttons["delete"] = self._button(
            left_actions, "Delete", self.delete_selected,
            background="#FEF2F2", foreground=COLORS["danger"],
            active_background="#FEE2E2",
        )
        self.buttons["delete"].pack(side="left", padx=4)
        self.buttons["up"] = self._button(left_actions, "↑ Move", lambda: self.move_selected(-1))
        self.buttons["up"].pack(side="left", padx=4)
        self.buttons["down"] = self._button(left_actions, "↓ Move", lambda: self.move_selected(1))
        self.buttons["down"].pack(side="left", padx=4)
        self.buttons["copy_selected"] = self._button(
            left_actions, "Copy Selected", self.copy_selected
        )
        self.buttons["copy_selected"].pack(side="left", padx=4)

        self.buttons["copy_next"] = self._button(
            toolbar,
            "COPY NEXT  →",
            self.copy_next,
            background=COLORS["blue"],
            foreground="#FFFFFF",
            active_background=COLORS["blue_hover"],
            padx=22,
            pady=13,
            font_size=10,
        )
        self.buttons["copy_next"].pack(side="right", padx=(12, 0))

        info = tk.Frame(content, bg=COLORS["background"])
        info.pack(fill="x", pady=(0, 9))
        tk.Label(
            info,
            text="QUICK TEXT QUEUE",
            bg=COLORS["background"],
            fg=COLORS["navy"],
            font=("Segoe UI", 11, "bold"),
        ).pack(side="left")
        tk.Label(
            info,
            textvariable=self.count_var,
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(11, 0))
        tk.Label(
            info,
            text="Drag a row to change its copy order",
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("Segoe UI", 9),
        ).pack(side="right")

        table_card = tk.Frame(
            content,
            bg=COLORS["surface"],
            highlightthickness=1,
            highlightbackground=COLORS["line"],
        )
        table_card.pack(fill="both", expand=True)

        columns = ("number", "title", "preview")
        self.tree = ttk.Treeview(
            table_card,
            columns=columns,
            show="headings",
            selectmode="browse",
            style="Queue.Treeview",
        )
        self.tree.heading("number", text="#", anchor="center")
        self.tree.heading("title", text="NAME", anchor="w")
        self.tree.heading("preview", text="TEXT", anchor="w")
        self.tree.column("number", width=58, minwidth=50, stretch=False, anchor="center")
        self.tree.column("title", width=210, minwidth=140, stretch=False, anchor="w")
        self.tree.column("preview", width=680, minwidth=260, stretch=True, anchor="w")
        self.tree.tag_configure("even", background="#FFFFFF")
        self.tree.tag_configure("odd", background="#F8FAFC")

        scrollbar = ttk.Scrollbar(
            table_card,
            orient="vertical",
            command=self.tree.yview,
            style="Queue.Vertical.TScrollbar",
        )
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side="left", fill="both", expand=True, padx=(7, 0), pady=7)
        scrollbar.pack(side="right", fill="y", padx=(0, 7), pady=7)

        self.tree.bind("<<TreeviewSelect>>", self._on_selection_changed)
        self.tree.bind("<Double-1>", self._on_double_click)
        self.tree.bind("<ButtonPress-1>", self._on_drag_start, add="+")
        self.tree.bind("<ButtonRelease-1>", self._on_drag_drop, add="+")

        footer = tk.Frame(content, bg=COLORS["background"], pady=12)
        footer.pack(fill="x")
        tk.Label(
            footer,
            textvariable=self.status_var,
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("Segoe UI", 9),
        ).pack(side="left")
        tk.Label(
            footer,
            textvariable=self.next_var,
            bg=COLORS["background"],
            fg=COLORS["blue"],
            font=("Segoe UI", 9, "bold"),
        ).pack(side="right")

    def refresh(self, selected_id: int | None = None) -> None:
        items = self.store.list_items()
        self.items_by_id = {item.id: item for item in items}
        for child in self.tree.get_children():
            self.tree.delete(child)

        for index, item in enumerate(items):
            preview = item.content.replace("\r\n", "\n").replace("\r", "\n")
            preview = preview.replace("\n", "  ↵  ").replace("\t", "    ")
            if len(preview) > 180:
                preview = preview[:177] + "..."
            self.tree.insert(
                "",
                "end",
                iid=str(item.id),
                values=(index + 1, item.title, preview),
                tags=("even" if index % 2 == 0 else "odd",),
            )

        self.count_var.set(f"{len(items)} saved text{'s' if len(items) != 1 else ''}")
        next_item = self.store.next_item()
        self.next_var.set(
            f"NEXT UP  ·  {next_item.title}" if next_item else "NEXT UP  ·  —"
        )

        if selected_id is not None and str(selected_id) in self.tree.get_children():
            self.tree.selection_set(str(selected_id))
            self.tree.focus(str(selected_id))
            self.tree.see(str(selected_id))
        self._update_button_states()

    def _selected_id(self) -> int | None:
        selection = self.tree.selection()
        if not selection:
            return None
        try:
            return int(selection[0])
        except ValueError:
            return None

    def _update_button_states(self) -> None:
        item_id = self._selected_id()
        enabled = item_id is not None and item_id in self.items_by_id
        for key in ("edit", "delete", "up", "down", "copy_selected"):
            self.buttons[key].configure(state="normal" if enabled else "disabled")
        if enabled:
            ids = [int(value) for value in self.tree.get_children()]
            index = ids.index(item_id)
            self.buttons["up"].configure(state="normal" if index > 0 else "disabled")
            self.buttons["down"].configure(
                state="normal" if index < len(ids) - 1 else "disabled"
            )
        self.buttons["copy_next"].configure(
            state="normal" if self.items_by_id else "disabled"
        )

    def _on_selection_changed(self, _event=None) -> None:
        self._update_button_states()

    def _on_double_click(self, event) -> None:
        if self.tree.identify_region(event.x, event.y) == "cell":
            self.edit_selected()

    def _on_drag_start(self, event) -> None:
        if self.tree.identify_region(event.x, event.y) not in ("cell", "tree"):
            self._drag_item_id = None
            return
        row_id = self.tree.identify_row(event.y)
        try:
            self._drag_item_id = int(row_id) if row_id else None
        except ValueError:
            self._drag_item_id = None

    def _on_drag_drop(self, event) -> None:
        dragged_id = self._drag_item_id
        self._drag_item_id = None
        if dragged_id is None:
            return
        target_row = self.tree.identify_row(event.y)
        if not target_row:
            return
        try:
            target_id = int(target_row)
        except ValueError:
            return
        if dragged_id == target_id:
            return

        ordered_ids = [int(value) for value in self.tree.get_children()]
        if dragged_id not in ordered_ids or target_id not in ordered_ids:
            return
        old_index = ordered_ids.index(dragged_id)
        target_index = ordered_ids.index(target_id)
        ordered_ids.pop(old_index)
        # Dropping downward places the dragged row after the target; dropping
        # upward places it before the target.
        insert_at = target_index if old_index > target_index else target_index
        ordered_ids.insert(insert_at, dragged_id)
        self.store.reorder(ordered_ids)
        self.refresh(selected_id=dragged_id)
        self.status_var.set("Queue order updated.")

    def add_text(self) -> None:
        self._open_editor()

    def edit_selected(self) -> None:
        item_id = self._selected_id()
        item = self.items_by_id.get(item_id) if item_id is not None else None
        if item is None:
            return
        self._open_editor(item)

    def _open_editor(self, item: QuickTextItem | None = None) -> None:
        dialog = tk.Toplevel(self.root)
        dialog.title("Edit Quick Text" if item else "Add Quick Text")
        dialog.configure(bg=COLORS["background"])
        dialog.resizable(True, True)
        dialog.transient(self.root)
        dialog.grab_set()
        dialog.geometry("620x470")

        container = tk.Frame(dialog, bg=COLORS["background"], padx=22, pady=20)
        container.pack(fill="both", expand=True)

        tk.Label(
            container,
            text="Edit Quick Text" if item else "Add Quick Text",
            bg=COLORS["background"],
            fg=COLORS["navy"],
            font=("Segoe UI", 16, "bold"),
        ).pack(anchor="w")
        tk.Label(
            container,
            text="Give this entry a short name, then enter the text to copy.",
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("Segoe UI", 9),
        ).pack(anchor="w", pady=(4, 15))

        tk.Label(
            container,
            text="NAME",
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("Segoe UI", 8, "bold"),
        ).pack(anchor="w")
        title_entry = tk.Entry(
            container,
            font=("Segoe UI", 10),
            relief="flat",
            bg="#FFFFFF",
            fg=COLORS["text"],
            insertbackground=COLORS["text"],
            highlightthickness=1,
            highlightbackground=COLORS["line"],
            highlightcolor=COLORS["blue"],
        )
        title_entry.pack(fill="x", ipady=9, pady=(5, 14))
        title_entry.insert(0, item.title if item else "")

        tk.Label(
            container,
            text="TEXT TO COPY",
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("Segoe UI", 8, "bold"),
        ).pack(anchor="w")
        text_frame = tk.Frame(
            container,
            bg="#FFFFFF",
            highlightthickness=1,
            highlightbackground=COLORS["line"],
        )
        text_frame.pack(fill="both", expand=True, pady=(5, 15))
        content_box = tk.Text(
            text_frame,
            wrap="word",
            undo=True,
            font=("Segoe UI", 10),
            relief="flat",
            bd=0,
            padx=10,
            pady=10,
            bg="#FFFFFF",
            fg=COLORS["text"],
            insertbackground=COLORS["text"],
        )
        content_box.pack(fill="both", expand=True)
        if item:
            content_box.insert("1.0", item.content)

        button_row = tk.Frame(container, bg=COLORS["background"])
        button_row.pack(fill="x")
        tk.Button(
            button_row,
            text="Cancel",
            command=dialog.destroy,
            bg="#FFFFFF",
            fg=COLORS["text"],
            activebackground="#F1F5F9",
            relief="flat",
            bd=0,
            padx=18,
            pady=10,
            cursor="hand2",
            font=("Segoe UI", 9, "bold"),
        ).pack(side="right", padx=(8, 0))

        def save() -> None:
            title = title_entry.get().strip() or "Text"
            content = content_box.get("1.0", "end-1c")
            if not content.strip():
                messagebox.showwarning(
                    APP_NAME,
                    "Enter some text before saving.",
                    parent=dialog,
                )
                content_box.focus_set()
                return
            try:
                if item is None:
                    saved_id = self.store.add(title, content)
                    message = "Text added to the queue."
                else:
                    self.store.update(item.id, title, content)
                    saved_id = item.id
                    message = "Text updated."
            except Exception as error:
                messagebox.showerror(APP_NAME, str(error), parent=dialog)
                return
            dialog.destroy()
            self.refresh(selected_id=saved_id)
            self.status_var.set(message)

        tk.Button(
            button_row,
            text="Save Text",
            command=save,
            bg=COLORS["blue"],
            fg="#FFFFFF",
            activebackground=COLORS["blue_hover"],
            activeforeground="#FFFFFF",
            relief="flat",
            bd=0,
            padx=20,
            pady=10,
            cursor="hand2",
            font=("Segoe UI", 9, "bold"),
        ).pack(side="right")
        dialog.bind("<Control-Return>", lambda _event: save())
        dialog.bind("<Escape>", lambda _event: dialog.destroy())
        title_entry.focus_set()
        dialog.update_idletasks()
        x = self.root.winfo_rootx() + max(0, (self.root.winfo_width() - dialog.winfo_width()) // 2)
        y = self.root.winfo_rooty() + max(0, (self.root.winfo_height() - dialog.winfo_height()) // 2)
        dialog.geometry(f"+{x}+{y}")

    def delete_selected(self) -> None:
        item_id = self._selected_id()
        item = self.items_by_id.get(item_id) if item_id is not None else None
        if item is None:
            return
        confirmed = messagebox.askyesno(
            APP_NAME,
            f'Delete "{item.title}" from the TXT queue?',
            parent=self.root,
        )
        if not confirmed:
            return
        self.store.delete(item.id)
        remaining = self.store.list_items()
        selected_id = remaining[min(item.position, len(remaining) - 1)].id if remaining else None
        self.refresh(selected_id=selected_id)
        self.status_var.set("Text deleted.")

    def move_selected(self, direction: int) -> None:
        item_id = self._selected_id()
        if item_id is None:
            return
        ordered_ids = [int(value) for value in self.tree.get_children()]
        if item_id not in ordered_ids:
            return
        index = ordered_ids.index(item_id)
        target = index + direction
        if target < 0 or target >= len(ordered_ids):
            return
        ordered_ids[index], ordered_ids[target] = ordered_ids[target], ordered_ids[index]
        self.store.reorder(ordered_ids)
        self.refresh(selected_id=item_id)
        self.status_var.set("Queue order updated.")

    def _put_on_clipboard(self, item: QuickTextItem) -> bool:
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append(item.content)
            self.root.update()
        except tk.TclError as error:
            messagebox.showerror(
                APP_NAME,
                f"Could not copy text to the Windows clipboard.\n\n{error}",
                parent=self.root,
            )
            return False
        return True

    def copy_selected(self) -> None:
        item_id = self._selected_id()
        item = self.items_by_id.get(item_id) if item_id is not None else None
        if item is None:
            return
        if self._put_on_clipboard(item):
            self.status_var.set(f'Copied selected text: {item.title}')
            # Manual Copy Selected intentionally does not move the sequential
            # queue pointer. Only Copy Next advances the queue.

    def copy_next(self) -> None:
        item = self.store.next_item()
        if item is None:
            self.status_var.set("Queue is empty. Add a text first.")
            return
        if not self._put_on_clipboard(item):
            return
        self.store.mark_copied(item.id)
        items = self.store.list_items()
        index = next(
            (i for i, entry in enumerate(items) if entry.id == item.id),
            0,
        )
        next_item = self.store.next_item()
        self.next_var.set(
            f"NEXT UP  ·  {next_item.title}" if next_item else "NEXT UP  ·  —"
        )
        self.status_var.set(
            f"Copied {index + 1} of {len(items)}  ·  {item.title}"
        )

    def close(self) -> None:
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    FloatClipTXTApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
