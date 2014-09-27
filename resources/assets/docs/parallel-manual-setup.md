<!--

title: Manually setting up parallelism
last_updated: Feb 2, 2013

-->

If you want the benefits of parallel testing, and you're not
using one of our automatically supported test runners, or if
you've overridden our test commands, you'll still be able to set up parallelism and reduce your test run-times.

To begin with, you'll need to turn on parallelism from your project's settings page.
Go to **Project Settings > Parallelism** to adjust the settings.

## Splitting your test suite

When you use CircleCI's parallelization, we run your code on multiple separate VMs.
To use parallelism, you make your test runner run only a subset of tests on each VM.
There are two mechanisms for splitting tests among nodes:  Using the `files`
configuration modifier - a very simply and straightforward way for most use cases, and
using parallelism environment variables - aimed for the more complex scenarios.

## Using configuration `files` modifier

Parallelizing test runners that accept file names is straightforward!  The `files` modifier
can list paths to the test files, and CircleCI will run the test runners with different test files in each node.
For example, to parallelize an rspec command, you can set the following:

```
test:
  override:
    - bundle exec rspec:
        parallel: true
        files:
          - spec/unit/sample.rb   # can be a direct path to file
          - spec/**/*.rb          # or a glob (ruby globs)
```

In this example, we will run `bundle exec rspec` in all nodes appended with
roughly `1/N` of the files on each VM.

By default, the file arguments will be appended to the end of the command.
Support for positional arguments is coming very soon.

## Using environment variables

For more control over parallelism, we use environment variables to denote the number of VMs and to identify each one, and you can access these from your test runner:

<dl>
  <dt>
    `CIRCLE_NODE_TOTAL`
  </dt>
  <dd>
    is the total number of parallel VMs being used to run your tests on each push.
  </dd>
  <dt>
    `CIRCLE_NODE_INDEX`
  </dt>
  <dd>
    is the index of the particular VM.
    `CIRCLE_NODE_INDEX`
    is indexed from zero.
  </dd>
</dl>

### A simple example

If you want to run the two commands
`rake spec`
and
`npm test`
in parallel, you can use a bash case statement:

```
test:
  override:
    - case $CIRCLE_NODE_INDEX in 0) rake spec ;; 1) npm test ;; esac:
        parallel: true
```

Note the final colon, and
`parallel: true`
on the next line.
This is a command modifier which tells circle that the command should be run in parallel on all test machines. It defaults to true for commands in the machine, checkout, dependencies and database build phases, and it defaults to false for commands in the test and deployment phases.

Obviously, this is slightly limited because it's hard-coded to
only work with two nodes, and the test time might not balance
across all nodes equally.

### Balancing

A more powerful version evenly splits all test files across N nodes. We recommend you write a script that does something like:

```
#!/bin/bash

i=0
files=()
for file in $(find ./test -name "*.py" | sort)
do
  if [ $(($i % $CIRCLE_NODE_TOTAL)) -eq $CIRCLE_NODE_INDEX ]
  then
    files+=" $file"
  fi
  ((i++))
done

test-runner ${files[@]}
```

This script partitions the test files into N equally sized buckets, and calls "test-runner" on the bucket for this machine.

## Contact Us

If you set this up for a library or framework that we should be
able to infer automatically, please
[contact us](mailto:sayhi@circleci.com).
We are always interested in adding support for more languages and frameworks.