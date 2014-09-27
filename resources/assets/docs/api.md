<!--

title: CircleCI REST API
last_updated: Aug 23, 2012

-->

## [The CircleCI API](#intro)

The CircleCI API is a RESTy, fully-featured API that allows you to do almost anything in CircleCI.
You can access all information and trigger all actions.
The only thing we don't provide access to is billing functions, which must be done from the CircleCI web UI.

## [Getting started](#getting-started)

1.  Add an API token from your [account dashboard](/account/api).
2.  To test it,
    [view it in your browser](/api/v1/me)
    or call the API using `curl`:

    ```
$ curl https://circleci.com/api/v1/me?circle-token=:token
```

3.  You should see a response like the following:
    ```
{
  "user_key_fingerprint" : null,
  "days_left_in_trial" : -238,
  "plan" : "p16",
  "trial_end" : "2011-12-28T22:02:15Z",
  "basic_email_prefs" : "smart",
  "admin" : true,
  "login" : "pbiggar"
}
```

## [Making calls](#calling)

All API calls are made in the same way, by making standard HTTP calls, using JSON, a content-type, and your API token.
All CircleCI API endpoints begin with `"https://circleci.com/api/v1/"`.

## [Authentication](#authentication)

To authenticate, add an API token using your [account dashboard](/account/api). To use the API token, add it to the
`circle-token` query param, like so:

```
curl https://circleci.com/api/v1/me?circle-token=:token
```

## [Accept header](#accept-header)

If you specify no accept header, we'll return human-readable JSON with comments.
If you prefer to receive compact JSON with no whitespace or comments, add the `"application/json" Accept header`.
Using `curl`:

```
curl https://circleci.com/api/v1/me?circle-token=:token -H "Accept: application/json"
```

## [User](#user)

{{ api_data.me | api-endpoint }}

## [Projects](#projects)

{{ api_data.projects | api-endpoint }}

## [Recent Builds Across All Projects](#recent-builds)

{{ api_data.recent_builds | api-endpoint }}

## [Recent Builds For a Single Project](#recent-builds-project)

{{ api_data.project | api-endpoint }}

You can narrow the builds to a single branch by appending /tree/:branch to the url:
`https://circleci.com/api/v1/project/:username/:project/tree/:branch`

The branch name should be url-encoded.

## [Single Build](#build)

<span class='label label-info'>Note:</span> This is also the payload for the [notification webhooks](/docs/configuration#notify), in which case this object is the value to a key named 'payload'.

{{ api_data.build | api-endpoint }}

## [Artifacts of a Build](#build-artifacts)

{{ api_data.artifacts | api-endpoint }}

## [Retry a Build](#retry-build)

{{ api_data.retry_build | api-endpoint }}

You can retry a build with ssh by swapping "retry" with "ssh":
`https://circleci.com/api/v1/project/:username/:project/:build_num/ssh`

## [Cancel a Build](#cancel-build)

{{ api_data.cancel_build | api-endpoint }}

## [Trigger a new Build](#new-build)

<span class='label label-info'>Note:</span> [Optional build parameters can be set using an experimental API](/docs/parameterized-builds)

{{ api_data.project_branch | api-endpoint }}

## [Clear Cache](#clear-cache)

{{ api_data.project_build_cache | api-endpoint}}

<!-- TODO: Custom filter or something for this -->

## [Summary](#summary)

All Circle API endpoints begin with
    `"https://circleci.com/api/v1/"`.

<dl class="dl-horizontal"></dl>
<dt>
  GET: /me
</dt>
<dd>
  Provides information about the signed in user.
</dd>
<dt>
  GET: /projects
</dt>
<dd>
  List of all the projects you're following on CircleCI, with build information organized by branch.
</dd>
<dt>
  GET: /project/:username/:project
</dt>
<dd>
  Build summary for each of the last 30 builds for a single git repo.
</dd>
<dt>
  GET: /recent-builds
</dt>
<dd>
  Build summary for each of the last 30 recent builds, ordered by build_num.
</dd>
<dt>
  GET: /project/:username/:project/:build_num
</dt>
<dd>
  Full details for a single build. The response includes all of the fields from the build summary. This is also the payload for the [notification webhooks](/docs/configuration#notify), in which case this object is the value to a key named 'payload'.
</dd>
<dt>
  GET: /project/:username/:project/:build_num/artifacts
</dt>
<dd>
  List the artifacts produced by a given build.
</dd>
<dt>
  POST: /project/:username/:project/:build_num/retry
</dt>
<dd>
  Retries the build, returns a summary of the new build.
</dd>
<dt>
  POST: /project/:username/:project/:build_num/cancel
</dt>
<dd>
  Cancels the build, returns a summary of the build.
</dd>
<dt>
  POST: /project/:username/:project/tree/:branch
</dt>
<dd>
  Triggers a new build, returns a summary of the build. [Optional build parameters can be set using an experimental API](/docs/parameterized-builds).
</dd>
<dt>
  DELETE: /project/:username/:project/build-cache
</dt>
<dd>
  Clears the cache for a project
</dd>
<dt>
  POST: /user/ssh-key
</dt>
<dd>
  Adds a CircleCI key to your Github User account.
</dd>
<dt>
  POST: /user/heroku-key
</dt>
<dd>
  Adds your Heroku API key to CircleCI, takes apikey as form param name.
</dd>
