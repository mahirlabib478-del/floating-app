# FloatClip TXT Desktop

A Windows desktop version of the TXT / Quick Text Queue from FloatClip Android. This app contains only the TXT queue system.

## Included

- Add, edit, and delete named text entries.
- Reorder entries by dragging rows, or with Move Up / Move Down.
- Copy Selected copies one entry without changing the sequential queue position.
- Copy Next copies the next entry in the current order.
- After the final entry, Copy Next loops back to the first entry.
- The next position is tracked by the entry's unique ID, so editing, reordering, or deleting other entries does not break the queue.
- Text entries and the last-copied position are saved automatically in a local SQLite database.

## Run from source

1. Install Python 3.10 or newer on Windows. Make sure Tkinter is included (it is included with the standard python.org Windows installer).
2. Double-click run_windows.bat, or run: python floatclip_txt.py

No third-party packages are needed to run the app.

## Build a standalone EXE

1. Install Python 3.12 (recommended).
2. Double-click build_windows.bat.
3. The standalone application will be created at dist\FloatClipTXT.exe.

## Data location

The database is stored in:

%APPDATA%\FloatClipTXT\floatclip_txt.db

Closing or reopening the application does not delete saved texts or the sequential copy position.

## Keyboard shortcuts

- Ctrl + N: Add Text
- Ctrl + Enter: Copy Next
- Delete: Delete the selected entry

