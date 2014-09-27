<!--

title: Introduction to Continuous Deployment
last_updated: March 12, 2014

-->

Circle enables you to automatically deploy after green builds.
We have customers deploying to PaaSes such as Heroku, Engine Yard,
and Google App Engine, hosted cloud servers (like EC2 and Rackspace),
and to private and colo servers&mdash;even behind firewalls.
Some use tools like Capistrano, Fabric, and Paver.

However you want to deploy your code, Circle makes
Continuous Deployment easy.

### Deployment syntax

Tell Circle about your deployment requirements in the `deployment`
section of your [circle.yml](/docs/configuration) file.
Within this section, you can can define custom deployment steps for each
branch, directing Circle to use specific deployment tools or custom scripts.
Deployment commands are triggered only after a successful build.

The following example will run the `deploy-to-production.sh`
script when the tests pass on the master branch and run the
`deploy-to-staging.sh` script when the tests pass on the staging branch.

```
deployment:
  production: # just a label; label names are completely up to you
    branch: master
    commands:
      - ./deploy-to-production.sh
  staging:
    branch: staging
    commands:
      - ./deploy-to-staging.sh
```

### Deploy over SSH

First you need to upload your SSH keys from your project's
**Project Settings > SSH keys** page in the Circle UI.
Circle will automatically add them to the `ssh-agent`,
so they are available for forwarding.

You can list commands or include bash scripts for SSH deployment in the
`circle.yml` file.
You can also use tools such as Capistrano or Fabric when deploying to
private servers or to EC2, Rackspace, and other cloud hosting providers.

You can use your existing Capistrano recipes, for example:

```
deployment:
  production:
    branch: master
    commands:
      - bundle exec cap production deploy
```

Here's an example using Fabric:

```
deployment:
  production:
    branch: master
    commands:
      - fab deploy
```

### Deploy to a PaaS

Circle has customers deploying to Heroku, Engine Yard, Google App Engine, Elastic Beanstalk, Dot Cloud, Nodejistu and other PaaSes. We have detailed instructions on
[deployment to Heroku](/docs/continuous-deployment-with-heroku)
and [deployment to Google App Engine](/docs/deploy-google-app-engine).
If you'd like help setting up your deployment, please
[contact us](mailto:sayhi@circleci.com).

### Firewalls, webhooks, and Circle's Public IP address ranges

You may need Circle's AWS information if you have a firewalled server that you need to access as part of your builds.

Circle is hosted in EC2's US East region, so our Amazon
[EC2 public IP address ranges](https://forums.aws.amazon.com/ann.jspa?annID=1701)
are there.
(Be aware that this link can sometimes be broken when Amazon make updates.
In such cases, you can usually access the list via the service's
[forum announcements](https://forums.aws.amazon.com/forum.jspa?forumID=30)
page.)

Circle's AWS security group has id sg-f98a8290 and is named www. Our account id is 183081753049.

Use the IP address ranges and security group number as needed to configure
a physical or in-the-cloud server under your control.
If you run into any problems, [contact us](mailto:sayhi@circleci.com)
and we'll be happy to help.
