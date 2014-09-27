<!--

title: CircleCI is running the wrong Ruby commands
last_updated: Feb 3, 2013

-->

CircleCI infers your test settings from the code in your repository, typically looking at the packages you use.
Most problems occur when there is some error in how the packages are specified.

If you require custom commands, it obviously won't be possible for CircleCI to infer them correctly.
In that case, you can [specify your commands](/docs/configuration)
using a `circle.yml` file.

If you feel we should have inferred your commands correctly, your project may diverge slightly from what we expect.
Some of the most common problems:

## Do you have a Gemfile.lock checked in?

If you only have a Gemfile checked in, it is easy for us to make mistakes.
This commonly causes us to call `spec` instead of `rspec`,
or use the wrong database.
You should check in your Gemfile.lock if possible, or manually add your test steps.

## Have you listed all your dependencies?

CircleCI always prefixes its commands with `bundle exec`.
This ensures that the only gems used are the ones specified in the Gemfile.
Unfortunately, developers commonly omit gems from their Gemfiles, but do not notice because they have them installed locally.
Run your test command locally with the `bundle exec`
prefix to check this (if it works locally with that command, this isn't the problem).

## Are you using the correct groups?

Bundler only requires gems that are in the `:test` group.
This includes all gems that have no specified group, or that explicitly specify the
`:test` group.
A common problem is to need gems which have only been put in the `:development`
or `:assets` group.

## Do you have two databases in your :test group?

This can often lead to incorrectly setting up databases, in particular, creating an incorrect `database.yml` file.
