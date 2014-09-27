# TODO: Make these annotations on defapi

window.circle_api_data =
    me:
      try_it: true
      url: "/api/v1/me"
      description: "Provides information about the signed in user."
      method: "GET"
      response: """
        {
          "basic_email_prefs" : "smart", // can be "smart", "none" or "all"
          "login" : "pbiggar" // your github username
        }
        """
    projects:
      try_it: true
      url: "/api/v1/projects"
      description: "List of all the projects you're following on CircleCI, with build information organized by branch."
      method: "GET"
      response: """
        [ {
          "vcs_url": "https://github.com/circleci/mongofinil",
          "followed": true, // true if you follow this project in CircleCI
          "username": "circleci",
          "reponame": "mongofinil",
          "branches" : {
            "master" : {
              "pusher_logins" : [ "pbiggar", "arohner" ], // users who have pushed
              "last_non_success" : { // last failed build on this branch
                "pushed_at" : "2013-02-12T21:33:14Z",
                "vcs_revision" : "1d231626ba1d2838e599c5c598d28e2306ad4e48",
                "build_num" : 22,
                "outcome" : "failed",
                },
              "last_success" : { // last successful build on this branch
                "pushed_at" : "2012-08-09T03:59:53Z",
                "vcs_revision" : "384211bbe72b2a22997116a78788117b3922d570",
                "build_num" : 15,
                "outcome" : "success",
                },
              "recent_builds" : [ { // last 5 builds, ordered by pushed_at (decreasing)
                "pushed_at" : "2013-02-12T21:33:14Z",
                "vcs_revision" : "1d231626ba1d2838e599c5c598d28e2306ad4e48",
                "build_num" : 22,
                "outcome" : "failed",
                }, {
                "pushed_at" : "2013-02-11T03:09:54Z",
                "vcs_revision" : "0553ba86b35a97e22ead78b0d568f6a7c79b838d",

                "build_num" : 21,
                "outcome" : "failed",
                }, ... ],
              "running_builds" : [ ] // currently running builds
            }
          }
        }, ... ]
             """
    project:
      url: "/api/v1/project/:username/:project"
      description: "Build summary for each of the last 30 builds for a single git repo."
      method: "GET"
      params: [
            name: "limit"
            description: "The number of builds to return. Maximum 100, defaults to 30."
            example: 20
          ,
            name: "offset"
            description: "The API returns builds starting from this offset, defaults to 0."
            example: 5
          ]
      response: """
      [ {
        "vcs_url" : "https://github.com/circleci/mongofinil",
        "build_url" : "https://circleci.com/gh/circleci/mongofinil/22",
        "build_num" : 22,
        "branch" : "master",
        "vcs_revision" : "1d231626ba1d2838e599c5c598d28e2306ad4e48",
        "committer_name" : "Allen Rohner",
        "committer_email" : "arohner@gmail.com",
        "subject" : "Don't explode when the system clock shifts backwards",
        "body" : "", // commit message body
        "why" : "github", // short string explaining the reason we built
        "dont_build" : null, // reason why we didn't build, if we didn't build
        "queued_at" : "2013-02-12T21:33:30Z" // time build was queued
        "start_time" : "2013-02-12T21:33:38Z", // time build started running
        "stop_time" : "2013-02-12T21:34:01Z", // time build finished running
        "build_time_millis" : 23505,
        "username" : "circleci",
        "reponame" : "mongofinil",
        "lifecycle" : "finished",
        "outcome" : "failed",
        "status" : "failed",
        "retry_of" : null, // build_num of the build this is a retry of
        "previous" : { // previous build
          "status" : "failed",
          "build_num" : 21
        }, ... ]
        """
    recent_builds:
      try_it: true
      url: "/api/v1/recent-builds"
      description: "Build summary for each of the last 30 recent builds, ordered by build_num."
      method: "GET"
      params: [
          name: "limit"
          description: "The number of builds to return. Maximum 100, defaults to 30."
          example: 20
        ,
          name: "offset"
          description: "The API returns builds starting from this offset, defaults to 0."
          example: 5
        ]
      response: """
      [ {
        "vcs_url" : "https://github.com/circleci/mongofinil",
        "build_url" : "https://circleci.com/gh/circleci/mongofinil/22",
        "build_num" : 22,
        "branch" : "master",
        "vcs_revision" : "1d231626ba1d2838e599c5c598d28e2306ad4e48",
        "committer_name" : "Allen Rohner",
        "committer_email" : "arohner@gmail.com",
        "subject" : "Don't explode when the system clock shifts backwards",
        "body" : "", // commit message body
        "why" : "github", // short string explaining the reason we built
        "dont_build" : null, // reason why we didn't build, if we didn't build
        "queued_at" : "2013-02-12T21:33:30Z" // time build was queued
        "start_time" : "2013-02-12T21:33:38Z", // time build started
        "stop_time" : "2013-02-12T21:34:01Z", // time build finished
        "build_time_millis" : 23505,
        "username" : "circleci",
        "reponame" : "mongofinil",
        "lifecycle" : "finished",
        "outcome" : "failed",
        "status" : "failed",
        "retry_of" : null, // build_num of the build this is a retry of
        "previous" : { // previous build
          "status" : "failed",
          "build_num" : 21
        }, ... ]
        """
    build:
      url: "/api/v1/project/:username/:project/:build_num"
      description: "Full details for a single build. The response includes all of the fields from the build summary."
      method: "GET"
      response: """
        {
          "vcs_url" : "https://github.com/circleci/mongofinil",
          "build_url" : "https://circleci.com/gh/circleci/mongofinil/22",
          "build_num" : 22,
          "branch" : "master",
          "vcs_revision" : "1d231626ba1d2838e599c5c598d28e2306ad4e48",
          "committer_name" : "Allen Rohner",
          "committer_email" : "arohner@gmail.com",
          "subject" : "Don't explode when the system clock shifts backwards",
          "body" : "", // commit message body
          "why" : "github", // short string explaining the reason the build ran
          "dont_build" : null, // reason why we didn't build, if we didn't build
          "queued_at" : "2013-02-12T21:33:30Z" // time build was queued
          "start_time" : "2013-02-12T21:33:38Z", // time build started
          "stop_time" : "2013-02-12T21:34:01Z", // time build finished
          "build_time_millis" : 23505,
          "username" : "circleci",
          "reponame" : "mongofinil",
          "lifecycle" : "finished",
          "outcome" : "success", // all successful builds have "success"
          "status" : "success",
          "retry_of" : null, // build_num of the build this is a retry of
          "steps" : [ {
            "name" : "configure the build",
            "actions" : [ {
              "bash_command" : null,
              "run_time_millis" : 1646,
              "start_time" : "2013-02-12T21:33:38Z",
              "end_time" : "2013-02-12T21:33:39Z",
              "name" : "configure the build",
              "command" : "configure the build",
              "exit_code" : null,
              "type" : "infrastructure",
              "index" : 0,
              "status" : "success",
            } ] },

            "name" : "lein2 deps",
            "actions" : [ {
              "bash_command" : "lein2 deps",
              "run_time_millis" : 7555,
              "start_time" : "2013-02-12T21:33:47Z",
              "command" : "((lein2 :deps))",
              "messages" : [ ],
              "step" : 1,
              "exit_code" : 0,
              "end_time" : "2013-02-12T21:33:54Z",
              "index" : 0,
              "status" : "success",
              "type" : "dependencies",
              "source" : "inference",
              "failed" : null
            } ] },
            "name" : "lein2 trampoline midje",
            "actions" : [ {
              "bash_command" : "lein2 trampoline midje",
              "run_time_millis" : 2310,
              "continue" : null,
              "parallel" : true,
              "start_time" : "2013-02-12T21:33:59Z",
              "name" : "lein2 trampoline midje",
              "command" : "((lein2 :trampoline :midje))",
              "messages" : [ ],
              "step" : 6,
              "exit_code" : 1,
              "end_time" : "2013-02-12T21:34:01Z",
              "index" : 0,
              "status" : "failed",
              "timedout" : null,
              "infrastructure_fail" : null,
              "type" : "test",
              "source" : "inference",
              "failed" : true
            } ]
          } ],
          ...
        }
        """
    artifacts:
      url: "/api/v1/project/:username/:project/:build_num/artifacts"
      description: "List the artifacts produced by a given build."
      method: "GET"
      response: """
        [
          {
            node_index: 0,
            path: "/tmp/circle-artifacts.NHQxLku/cherry-pie.png",
            pretty_path: "$CIRCLE_ARTIFACTS/cherry-pie.png",
            url: "https://circleci.com/gh/circleci/mongofinil/22/artifacts/0/tmp/circle-artifacts.NHQxLku/cherry-pie.png"
          },
          {
            node_index: 0,
            path: "/tmp/circle-artifacts.NHQxLku/rhubarb-pie.png",
            pretty_path: "$CIRCLE_ARTIFACTS/rhubarb-pie.png",
            url: "https://circleci.com/gh/circleci/mongofinil/22/artifacts/0/tmp/circle-artifacts.NHQxLku/rhubarb-pie.png"
          }
        ]
        """
    retry_build:
      url: "/api/v1/project/:username/:project/:build_num/retry"
      description: "Retries the build, returns a summary of the new build."
      method: "POST"
      response: """
        {
          "vcs_url" : "https://github.com/circleci/mongofinil",
          "build_url" : "https://circleci.com/gh/circleci/mongofinil/23",
          "build_num" : 23,
          "branch" : "master",
          "vcs_revision" : "1d231626ba1d2838e599c5c598d28e2306ad4e48",
          "committer_name" : "Allen Rohner",
          "committer_email" : "arohner@gmail.com",
          "subject" : "Don't explode when the system clock shifts backwards",
          "body" : "", // commit message body
          "why" : "retry", // short string explaining the reason we built
          "dont_build" : null, // reason why we didn't build, if we didn't build
          "queued_at" : "2013-04-12T21:33:30Z" // time build was queued
          "start_time" : "2013-04-12T21:33:38Z", // time build started running
          "stop_time" : "2013-04-12T21:34:01Z", // time build finished running
          "build_time_millis" : 23505,
          "username" : "circleci",
          "reponame" : "mongofinil",
          "lifecycle" : "queued",
          "outcome" : null,
          "status" : "queued",
          "retry_of" : 22, // build_num of the build this is a retry of
          "previous" : { // previous build
            "status" : "failed",
            "build_num" : 22
          }
        }
        """
    cancel_build:
      url: "/api/v1/project/:username/:project/:build_num/cancel"
      description: "Cancels the build, returns a summary of the build."
      method: "POST"
      response: """
        {
          "vcs_url" : "https://github.com/circleci/mongofinil",
          "build_url" : "https://circleci.com/gh/circleci/mongofinil/26",
          "build_num" : 26,
          "branch" : "master",
          "vcs_revision" : "59c9c5ea3e289f2f3b0c94e128267cc0ce2d65c6",
          "committer_name" : "Allen Rohner",
          "committer_email" : "arohner@gmail.com",
          "subject" : "Merge pull request #6 from dlowe/master"
          "body" : "le bump", // commit message body
          "why" : "retry", // short string explaining the reason we built
          "dont_build" : null, // reason why we didn't build, if we didn't build
          "queued_at" : "2013-05-24T19:37:59.095Z" // time build was queued
          "start_time" : null, // time build started running
          "stop_time" : null, // time build finished running
          "build_time_millis" : null,
          "username" : "circleci",
          "reponame" : "mongofinil",
          "lifecycle" : "queued",
          "outcome" : "canceled",
          "status" : "canceled",
          "canceled" : true,
          "retry_of" : 25, // build_num of the build this is a retry of
          "previous" : { // previous build
            "status" : "success",
            "build_num" : 25
          }
        }
        """
    project_branch:
      url: "/api/v1/project/:username/:project/tree/:branch"
      description: "Triggers a new build, returns a summary of the build."
      method: "POST"
      response: """
        {
          "author_name": "Allen Rohner",
          "feature_flags": {},
          "build_url": "https://circleci.com/gh/circleci/mongofinil/54",
          "reponame": "mongofinil",
          "failed": null,
          "infrastructure_fail": false,
          "canceled": false,
          "all_commit_details": [
            {
              "author_name": "Allen Rohner",
              "commit": "f1baeb913288519dd9a942499cef2873f5b1c2bf",
              "author_login": "arohner",
              "committer_login": "arohner",
              "committer_name": "Allen Rohner",
              "body": "Minor version bump",
              "author_date": "2014-04-17T08:41:40Z",
              "committer_date": "2014-04-17T08:41:40Z",
              "commit_url": "https://github.com/circleci/mongofinil/commit/f1baeb913288519dd9a942499cef2873f5b1c2bf",
              "committer_email": "arohner@gmail.com",
              "author_email": "arohner@gmail.com",
              "subject": "Merge pull request #15 from circleci/minor-version-bump"
            }
          ],
          "previous": {
            "build_num": 53,
            "status": "success",
            "build_time_millis": 55413
          },
          "ssh_enabled": null,
          "author_email": "arohner@gmail.com",
          "why": "edit",
          "build_time_millis": null,
          "committer_email": "arohner@gmail.com",
          "parallel": 1,
          "retries": null,
          "compare": null,
          "dont_build": null,
          "committer_name": "Allen Rohner",
          "usage_queued_at": "2014-04-29T12:56:55.338Z",
          "branch": "master",
          "body": "Minor version bump",
          "author_date": "2014-04-17T08:41:40Z",
          "node": null,
          "committer_date": "2014-04-17T08:41:40Z",
          "start_time": null,
          "stop_time": null,
          "lifecycle": "not_running",
          "user": {
            "email": "arohner@gmail.com",
            "name": "Allen Rohner",
            "login": "arohner",
            "is_user": true
          },
          "subject": "Merge pull request #15 from circleci/minor-version-bump",
          "messages": [],
          "job_name": null,
          "retry_of": null,
          "previous_successful_build": {
            "build_num": 53,
            "status": "success",
            "build_time_millis": 55413
          },
          "outcome": null,
          "status": "not_running",
          "vcs_revision": "f1baeb913288519dd9a942499cef2873f5b1c2bf",
          "build_num": 54,
          "username": "circleci",
          "vcs_url": "https://github.com/circleci/mongofinil",
          "timedout": false
        }
        """
    project_build_cache:
      url: "/api/v1/project/:username/:project/build-cache"
      description: "Clears the cache for a project"
      method: "DELETE"
      response: """
        {
          "status" : "build caches deleted"
        }
        """
    "user/ssh-key":
      description: "Adds a CircleCI key to your Github User account."
      method: "POST"
    "user/heroku-key":
      method: "POST"
      description: "Adds your Heroku API key to CircleCI, takes apikey as form param name."