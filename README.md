# scrabble

3D model of the famous game scrabble. Ready to be printed.

Currently, contains all the letters for Russian, English and Germain versions.
All stl-files have their complimentary version (`<file>-compl.stl`) for multi-color print.

## Adding new language

### Prerequisites

- [AdoptOpenJDK 11](https://adoptopenjdk.net/installation.html#)
- [Ammonite](https://ammonite.io/#Ammonite-REPL)
- [Solvespace.cli](https://github.com/solvespace/solvespace#installation)

### Adding new language

In order to add another language (and generate the letters), one need to fill the csv-file, named `<language>.csv` in folder `languages`.
This file should contain 2 columns: first on is letter, second is the point-value for the letter.

When the csv is ready, from root folder run `populate.cs` with language to generate
(e.g. for csv file `en.csv` the command will be `./populate.sc en`).
This will create `<language>` subfolder in folder `letters`.
Unfortunately the process is not perfect, and a human touch is needed, so after the letters are created 
(along with their complimentary parts for multi-color printing), one still need to open all the generated `slvs` files
and make sure that all the letters and points are within the boundaries, as well as export them as `stl` files. 