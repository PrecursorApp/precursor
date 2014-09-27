class CI.outer.Home extends CI.outer.Page
  lib: () =>
    _gaq.push(['_trackPageview', '/homepage'])
    mixpanel.track("Outer Home Page", {
      'window height': $( window ).height();
      })

  viewContext: =>
    features: [
      icon: "fa-magic"
      headline: "Quick setup"
      teaser: "Set up your CI server in 20 seconds, not two days. We detect test settings for a wide range of web apps, and set them up automatically on our servers."
    ,
      icon: "fa-bolt"
      headline: "Crazy fast tests"
      teaser: "Your productivity relies on fast test results, and CircleCI runs your tests faster than your Macbook Pro, EC2, your local server, or any other service."
    ,
      icon: "fa-bullhorn"
      headline: "Smart notifications"
      teaser: "CircleCI intelligently notifies you about just the pushes you care about, not all pushes, and does it over email, Hipchat, Campfire and more."
    ,
      icon: "fa-flask"
      headline: "Deep customization"
      teaser: "Real applications often deviate slightly from standard configurations, so CircleCI does too. Our configuration is so flexible that it's easy to tweak almost anything you need."
    ,
      icon: "fa-cog"
      headline: "Debug with ease"
      teaser: "When your tests are broken, we help you get them fixed. We auto-detect errors, have great support, and even allow you to SSH into our machines to test manually."
    ,
      icon: "fa-comments"
      headline: "Incredible support"
      teaser: "We respond to support requests immediately, every day. Most requests are responded to within an hour. No-one ever waits more than 12 hours for a response."
    ]  
