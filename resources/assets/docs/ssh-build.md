<!--

title: SSH access to builds
last_updated: Feb 2, 2013

-->

Often the best way to troubleshoot problems is to ssh into a
running or finished build to look at log files, running processes,
and so on.

You can do that with CircleCI!

Near the upper right corner of each build page, you'll find an
'enable ssh' button:

![](asset://img/outer/docs/ssh-build-button.png)

Clicking this button will start a new build with remote SSH
access enabled. After a few moments on the new build, you'll
see a section labeled 'SSH Info'. Inside this section,
you will find the host and port information:

![](asset://img/outer/docs/ssh-build-details.png)

Now you can ssh to the running build (using the same ssh key
that you use for GitHub) to perform whatever troubleshooting
you need to. **Your build commands will run as usual.**

After the build commands run, the build output will show another
special section labeled 'Wait for SSH', which repeats the host and
port information.

The build VM will remain available for **30 minutes after the build finishes running**
and then automatically shut down. (Or you can cancel it.)

#### Parallelism and SSH Builds

If your build has parallel steps, we launch more than one VM
to perform them. Thus, you'll see more than one 'Enable SSH' and
'Wait for SSH' section in the build output.

#### Debugging: "Permission denied (publickey)"

If you run into permission troubles trying to ssh to your build, try
these things:

##### Ensure that you can authenticate with github

Github makes it very easy to test that your keys are setup as expected.
Just run:

```
$ ssh git@github.com
```

and you should see:

```
Hi :username! You've successfully authenticated...
```

If you _don't_ see output like that, you need to start by
[troubleshooting your ssh keys with github](https://help.github.com/articles/error-permission-denied-publickey).

##### Ensure that you're authenticating as the correct user

If you have multiple github accounts, double-check that you are
authenticated as the right one! Again, using github's ssh service,
run ssh git@github.com and look at the output:

```
Hi :username! You've successfully authenticated...
```

In order to ssh to a circle build, the username must be one which has
access to the project being built!

If you're authenticating as the wrong user, you can probably resolve this
by offering a different ssh key with `ssh -i`. See the next section if
you need a hand figuring out which key is being offered.

##### Ensure that you're offering the correct key to circle

If you've verified that you can authenticate with github as the correct
user, but you're still getting "Permission denied" from CircleCI, you
may be offering the wrong credentials to us. (This can happen for
several reasons, depending on your ssh configuration.)

Figure out which key is being offered to github that authenticates you, by
running:

```
$ ssh -v git@github.com
```

In the output, look for a sequence like this:

```
debug1: Offering RSA public key: /Users/me/.ssh/id_rsa_github
<...>
debug1: Authentication succeeded (publickey).
```

This sequence indicates that the key /Users/me/.ssh/id_rsa_github is the one which
github accepted.

Next, run the ssh command for your circle build, but add the -v flag.
In the output, look for one or more lines like this:

```
debug1: Offering RSA public key: ...
```

Make sure that the key which github accepted (in our
example, /Users/me/.ssh/id_rsa_github) was also offered to CircleCI.

If it was not offered, you can specify it via the -i command-line
argument to ssh. For example:

```
$ ssh -i /Users/me/.ssh/id_rsa_github -p 64784 ubuntu@54.224.97.243
```

##### Nope, still broken

Drat! Well, [contact us](mailto:sayhi@circleci.com) and we'll try to help.
