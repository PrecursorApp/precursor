<!--

title: GitHub security and SSH keys
last_updated: May 1, 2013

-->

GitHub has two different SSH keys&mdash;a _deploy_ key and a _user_ key.
When you add a GitHub repository to CircleCI, we automatically add a deploy
key that references this repository.
For most customers, this is all CircleCI needs to run their tests.

Each deploy key is valid for only _one_ repository.
In contrast, a GitHub user key has access to _all_ of your GitHub repositories.

If your testing process refers to multiple repositories
(if you have a Gemfile that points at a  private `git` repository, for example),
CircleCI will be unable to check out such repositories with only a deploy key.
When testing requires access to different repositories, CircleCI will need a GitHub user key.

You can provide CircleCI with a GitHub user key on your project's
** Project Settings > Checkout SSH keys ** page.
CircleCI creates and associates this new SSH key with your GitHub user account
and then has access to all your repositories.

## User key security

CircleCI is serious when it comes to security.
We will never make your SSH keys public.

Remember that SSH keys should be shared only with trusted users.
Anyone that is a GitHub collaborator on a project employing user keys
can access your repositories as you.
Beware of someone stealing your code.

## User key access-related error messages

Here are common errors that indicate you need to add a user key.

**Python**: During the `pip install` step:

```
ERROR: Repository not found.
```

**Ruby**: During the `bundle install` step:

```
Permission denied (publickey).
```
