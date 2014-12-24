# precursor

## Usage

Install node dependencies (requires a recent version of node):

```
npm install
```

Download datomic from here https://my.datomic.com/downloads/free

unzip it and from within the directory run

```
bin/transactor config/samples/free-transactor-template.properties
```

Start the server:

```
lein run
```

That will start a server on port 8080 and start an nrepl on port 6005.


Compile the frontend clojurescript assets:

```
lein figwheel dev
```

Then go to http://localhost:8080

### Browser REPL

nrepl in to the nrepl server and run:

```
(pc.utils/connect-browser-weasel)
```

Then reload the app.
