# FloatClip TXT Desktop

A Windows desktop version of only the TXT / Quick Text Queue from FloatClip Android. The main screen is a spreadsheet-style single-column grid.

## Spreadsheet-style features

- The queue is displayed as row numbers and column A, like a simple spreadsheet.
- Double-click a single-line cell to edit it directly. Double-click a multi-line cell to open the full cell editor.
- Click Paste Rows once to import all non-empty lines from the clipboard as separate rows.
- If rows are selected, pasted rows are inserted immediately after the last selected row. If nothing is selected, they are appended.
- Ctrl+V while the grid is focused performs the same multi-row paste.
- Select multiple rows with Ctrl-click or Shift-click, then delete or copy them together.
- Drag selected rows to reorder them, or use the up/down buttons.
- Copy Selected copies selected cell values joined with newlines without advancing the sequential queue.
- Copy Next copies the next row and loops back to row 1 after the final row.
- All rows and the last-copied row ID are saved automatically in the existing SQLite database.

## Run from source

1. Install Python 3.10 or newer on Windows. Make sure Tkinter is included.
2. Run: python floatclip_txt.py

No third-party packages are needed to run the app.

## Build a standalone EXE

1. Install Python 3.12.
2. Run build_windows.bat.
3. The standalone application will be created at dist\FloatClipTXT.exe.

## Data location

%APPDATA%\FloatClipTXT\floatclip_txt.db

The updated desktop app uses the same database location as the previous FloatClip TXT desktop build, so existing saved rows and queue progress are retained.

## Keyboard shortcuts

- Ctrl + N: Add a row
- Ctrl + V: Paste clipboard lines as rows (when the grid is focused)
- Ctrl + A: Select all rows
- Ctrl + C: Copy selected rows
- F2: Open full cell editor
- Ctrl + Enter: Copy Next
- Delete: Delete selected rows
