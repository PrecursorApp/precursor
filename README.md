# CircleCI's frontend

This is an open-source mirror of the code that is running [CircleCI's](https://circleci.com) frontend. CircleCI provides powerful Continuous Integration and Deployment with easy setup and maintenance.

Feel free to fork and make contributions. We'll try to get them into the main application.

## Usage

First, start the server that will serve the compiled assets:

```
lein run
```

That will start a server on port 8080.

In your `/etc/hosts`, add the following line:

```
127.0.0.1 circlehost
```

That will let you use your locally compiled assets on https://circleci.com.


Next, compile the frontend clojurescript assets:

```
lein cljsbuild auto dev
```

Install node dependencies (requires a recent version of node):

```
npm install
```

Download all of the 3rd-party javascript dependencies:

```
node_modules/.bin/bower install
```

To test that everything worked, visit [http://circlehost:8080/assets/css/app.css.less](http://circlehost:8080/assets/css/app.css.less) and [http://circlehost:8080/assets/js/om-dev.js.stefon](http://circlehost:8080/assets/js/om-dev.js.stefon) in your browser.

Now you should have everything you need to start hacking on Circle's frontend!

Visit [https://circleci.com?use-local-assets=true&om-build-id=dev](https://circleci.com?use-local-assets=true&om-build-id=dev) in Chrome. You'll see a page with the text "Help". You'll need to click the shield in the URL bar and click "Load unsafe script".

If everything worked properly, you should see the normal CircleCI website. If things didn't work properly, please open an issue.

To get back to the website using the default assets, visit [https://circleci.com?use-local-assets=false&om-build-id=production](https://circleci.com?use-local-assets=false&om-build-id=production).
