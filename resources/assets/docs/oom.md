<!--

title: OOM killer ran
last_updated: Feb 2, 2013

-->

Your build contains a message that says the Linux Out-of-Memory (OOM)
killer ran.

The reason for this is your builds run in a VM with 2GB of
available RAM. If you go over that limit, Linux kills a process,
somewhat arbitrarily.

Unfortunately, there's no good way for us to know which process
was killed. While testing, it's commonly your application, or
DB, or web browser.

Hitting the RAM limit is typically a bug. If your tests actually
need more than 2GB of RAM, please [contact us](mailto:sayhi@circleci.com).

## Debugging

Use the [SSH button](/docs/ssh-build)
to ssh into a running build and run `top`.

Hit "shift+m" to sort by memory usage and watch what process is using the most memory while your tests run.

The number to pay attention to is the RES (short for resident) column. This tracks the actual ram used by a process. Note that the 2GB limit applies to the sum of all processes running in your container, not just a single process.

One thing to keep in mind is that the %MEM you see in top is the percentage of the entire machine, not just the container that your builds are running in. The OOM killer typically runs when a process uses up 2-3% of total memory.
