j = jasmine.getEnv()

# TODO write these for container plans
plans =
  p18:
    free_containers: ko.observable(1)
    container_cost: 50
    max_containers: ko.observable(100)
    price: 19
    type: 'containers'
  p19:
    free_containers: ko.observable(1)
    container_cost: 50
    max_containers: ko.observable(100)
    price: 49
    type: 'containers'
  p20:
    free_containers: ko.observable(2)
    container_cost: 50
    max_containers: ko.observable(100)
    price: 149
    type: 'containers'

testBilling = (plan, c, e) ->
  @expect((new CI.inner.Billing()).calculateCost(plan, c)).toEqual e

j.describe "calculateCost", ->
  j.it "should be the same as on the server", ->
    # no extra
    testBilling(plans.p18, 1, 19)
    testBilling(plans.p19, 1, 49)
    testBilling(plans.p20, 1, 149)
    testBilling(plans.p20, 2, 149)

    # minimum price
    testBilling(plans.p18, 0, 19)
    testBilling(plans.p19, 0, 49)
    testBilling(plans.p20, 0, 149)

    # extras
    testBilling(plans.p18, 2, 19 + 50 * 1)
    testBilling(plans.p18, 40, 19 + 50 * 39)
    testBilling(plans.p19, 2, 49 + 50 * 1)
    testBilling(plans.p19, 40, 49 + 50 * 39)
    testBilling(plans.p20, 4, 149 + 50 * 2)
    testBilling(plans.p20, 40, 149 + 50 * 38)

j.describe "ansiToHtml", ->
  t = CI.terminal
  j.it "shouldn't screw up simple text", ->
    @expect(t.ansiToHtml "").toEqual ""
    @expect(t.ansiToHtml "foo").toEqual "<span class='brblue'>foo</span>"

  j.it "should work for the following simple escape sequences", ->
    @expect(t.ansiToHtml "\u001b[1mfoo\u001b[m").toEqual "<span class='brblue'>foo</span>"
    @expect(t.ansiToHtml "\u001b[3mfoo\u001b[m").toEqual "<span class='brblue'><span class='italic'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[30mfoo\u001b[m").toEqual "<span class='brblue'><span class='white'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[31mfoo\u001b[m").toEqual "<span class='brblue'><span class='red'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[32mfoo\u001b[m").toEqual "<span class='brblue'><span class='green'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[33mfoo\u001b[m").toEqual "<span class='brblue'><span class='yellow'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[34mfoo\u001b[m").toEqual "<span class='brblue'><span class='blue'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[35mfoo\u001b[m").toEqual "<span class='brblue'><span class='magenta'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[36mfoo\u001b[m").toEqual "<span class='brblue'><span class='cyan'>foo</span></span>"
    @expect(t.ansiToHtml "\u001b[37mfoo\u001b[m").toEqual "<span class='brblue'><span class='white'>foo</span></span>"

  j.it "shouldn't leave an open span even when the escape isn't reset", ->
    @expect(t.ansiToHtml "\u001b[32mfoo").toEqual "<span class='brblue'><span class='green'>foo</span></span>"

  j.it "should cope with leading text", ->
    @expect(t.ansiToHtml "foo\u001b[32mbar").toEqual "<span class='brblue'>foo<span class='green'>bar</span></span>"

  j.it "should cope with trailing text, and correctly clear styles", ->
    @expect(t.ansiToHtml "\u001b[32mfoo\u001b[mbar").toEqual "<span class='brblue'><span class='green'>foo</span>bar</span>"
    @expect(t.ansiToHtml "\u001b[32mfoo\u001b[0mbar").toEqual "<span class='brblue'><span class='green'>foo</span>bar</span>"

  j.it "should allow multiple escapes in sequence", ->
    @expect(t.ansiToHtml "\u001b[1;3;32mfoo").toEqual "<span class='brblue'><span class='brgreen italic'>foo</span></span>"

  j.it "should allow independent changes to styles", ->
    @expect(t.ansiToHtml "\u001b[1;3;32mfoo\u001b[22mbar\u001b[23mbaz\u001b[39mbarney").toEqual "<span class='brblue'><span class='brgreen italic'>foo</span><span class='green italic'>bar</span><span class='green'>baz</span>barney</span>"

  j.it "should strip escapes it doesn't understand", ->
    # only 'm' escapes are known currently
    @expect(t.ansiToHtml "\u001b[1Mfoo").toEqual "<span class='brblue'>foo</span>"
    # no blinking
    @expect(t.ansiToHtml "\u001b[5mfoo").toEqual "<span class='brblue'>foo</span>"

  j.it "should 'animate' carriage returns", ->
    @expect(t.ansiToHtml "foo\nfoo\r").toEqual "<span class='brblue'>foo\n</span><span class='brblue'>foo\r</span>"
    @expect(t.ansiToHtml "foo\nfoo\rbar\n").toEqual "<span class='brblue'>foo\n</span><span class='brblue'>bar\n</span>"

  j.it "should not throw away \r\n ended lines", ->
    @expect(t.ansiToHtml "rn\r\nend").toEqual "<span class='brblue'>rn\r\n</span><span class='brblue'>end</span>"

  j.it "shouldn't short-circuit because of empty lines", ->
    @expect(t.ansiToHtml "first\n\nsecond\n").toEqual "<span class='brblue'>first\n\n</span><span class='brblue'>second\n</span>"

  j.it "shouldn't blow up if the first line is empty", ->
    @expect(t.ansiToHtml "\r\nfirst\n").toEqual "<span class='brblue'>\r\n</span><span class='brblue'>first\n</span>"

j.describe "githubAuthURL", ->
  j.it "should be the expect values", ->
    @expect(CI.github.authUrl()).toEqual "https://github.com/login/oauth/authorize?client_id=586bf699b48f69a09d8c&redirect_uri=http%3A%2F%2Fcirclehost%3A8080%2Fauth%2Fgithub%3Freturn-to%3D%252Ftests%252Finner%26CSRFToken%3D#{encodeURIComponent(encodeURIComponent(window.CSRFToken))}&scope=user%3Aemail%2Crepo"
    @expect(CI.github.authUrl([])).toEqual "https://github.com/login/oauth/authorize?client_id=586bf699b48f69a09d8c&redirect_uri=http%3A%2F%2Fcirclehost%3A8080%2Fauth%2Fgithub%3Freturn-to%3D%252Ftests%252Finner%26CSRFToken%3D#{encodeURIComponent(encodeURIComponent(window.CSRFToken))}&scope="


j.describe "plans page", ->
  j.describe "invoice list", ->
    it "should work", ->
      i = new CI.inner.Invoice
        amount_due: 14900
        currency: "usd"
        date:         1358984466
        paid: true
        period_start: 1358831737 # numbers chosen to be close to the date boundary
        period_end:   1358984466
      @expect(i.time_period()).toEqual "2013/01/22 - 2013/01/23"
      @expect(i.invoice_date()).toEqual "2013/01/23"

j.describe "CI.stringHelpers.trimMiddle", ->
  j.it "should not make strings longer", ->
    @expect(CI.stringHelpers.trimMiddle("four", 2)).toEqual "four"
  j.it "should make long strings short", ->
    twenty = "01234567890123456789"
    @expect(CI.stringHelpers.trimMiddle(twenty, 10).length).toEqual 10
    @expect(CI.stringHelpers.trimMiddle(twenty, 10)).toEqual "012...6789"

j.describe "time duration works", ->
  j.it "over 1 minute is correct", ->
    @expect(CI.time.as_duration(1000)).toEqual "00:01"
    @expect(CI.time.as_duration(100000)).toEqual "01:40"
    @expect(CI.time.as_duration(1000000)).toEqual "16:40"

j.describe "time stamp works", ->
  j.it "test timezone is 0000", ->
    @expect(CI.time.as_timestamp("1990-02-11T00:00:00.000Z")).toEqual "Sun, Feb 11 1990 12:00 AM +0000"
    @expect(CI.time.as_timestamp("1991-01-02T00:00:00.000Z")).toEqual "Wed, Jan 2 1991 12:00 AM +0000"
    @expect(CI.time.as_timestamp("2011-04-01T00:00:00.000Z")).toEqual "Fri, Apr 1 2011 12:00 AM +0000"


j.describe "headings get link anchors correctly", ->
  d = VM.docs
  console.log d
  j.it "should work for existing ids", ->
    h = "<h2 id='asd'>Some title</h2>"
    expected = '<h2 id="asd"><a href="#asd">Some title</a></h2>'
    @expect(d.addLinkTarget(h)[0].outerHTML).toEqual expected

  j.it "should work when there are no ids", ->
    h = "<h2>Some title</h2>"
    expected = '<h2 id="some-title"><a href="#some-title">Some title</a></h2>'
    @expect(d.addLinkTarget(h)[0].outerHTML).toEqual expected

  j.it "should work correctly with apostrophies", ->
    h = "<h2>Someone's title</h2>"
    expected = '<h2 id="someones-title"><a href="#someones-title">Someone\'s title</a></h2>'
    @expect(d.addLinkTarget(h)[0].outerHTML).toEqual expected

j.describe "setting project parallelism works", ->
  VM.current_user = ko.observable(new CI.inner.User({login: 'test-user'}))

  j.it "should handle trials", ->
    project = new CI.inner.Project
      parallel: 2
    project.billing.loadPlanData
      containers: 6
      template_properties:
        max_parallelism: 6
        price: null
        type: "trial"

    @expect(project.paid_parallelism()).toEqual 6
    @expect(project.plan().max_parallelism()).toEqual 6

    # Allow them to select 5x
    @expect(project.parallel_label_style(5).disabled()).not.toBe(true)

    # Don't allow them to select 8x
    @expect(project.parallel_label_style(8).disabled()).toEqual(true)

    @expect(project.show_upgrade_plan()).not.toBe(true)
    @expect(project.show_add_containers()).not.toBe(true)

  j.it "should handle container-type plans", ->
    project = new CI.inner.Project
      parallel: 2
    project.billing.loadPlanData
      template_properties:
        max_parallelism: 3
        price: null
        type: "containers"
      containers: 2

    @expect(project.paid_parallelism()).toEqual 2
    @expect(project.plan().max_parallelism()).toEqual 3

    @expect(project.parallel_label_style(2).disabled()).not.toBe(true)
    @expect(project.parallel_label_style(3).disabled()).toBe(true)
    @expect(project.parallel_label_style(4).disabled()).toBe(true)

    # Don't tell them to upgrade for things they can click
    project.focused_parallel(2)
    @expect(project.show_upgrade_plan()).not.toBe(true)
    @expect(project.show_add_containers()).not.toBe(true)

    # Don't tell them to upgrade their plan if it supports higher speed
    project.focused_parallel(3)
    @expect(project.show_upgrade_plan()).not.toBe(true)
    @expect(project.show_add_containers()).toBe(true)

    # Don't tell them to upgrade their speed if their plan doesn't supports it
    project.focused_parallel(4)
    @expect(project.show_upgrade_plan()).toBe(true)
    @expect(project.show_add_containers()).not.toBe(true)

    project.billing.containers(5)
    project.focused_parallel(2)
    @expect(project.show_upgrade_plan()).not.toBe(true)
    @expect(project.show_add_containers()).not.toBe(true)

    project.focused_parallel(3)
    @expect(project.show_upgrade_plan()).not.toBe(true)
    @expect(project.show_add_containers()).not.toBe(true)

    project.focused_parallel(4)
    @expect(project.show_upgrade_plan()).toBe(true)
    @expect(project.show_add_containers()).not.toBe(true)

j.describe "favicon", ->
 j.it "setting build status changes favicon", ->
   VM.current_page(new CI.inner.BuildPage({}))
   VM.build(new CI.inner.Build({}))

   VM.build().status("running")
   @expect(VM.build().favicon_color()).toBe("blue")
   @expect(VM.favicon.get_color()).toBe(VM.build().favicon_color())

   VM.build().status("failed")
   @expect(VM.build().favicon_color()).toBe("red")
   @expect(VM.favicon.get_color()).toBe(VM.build().favicon_color())


j.describe "AJAX CSRF", ->
 j.it "CSRF token should be present on requests to the server", ->
   @expect(CI.sendCSRFtoken({url: "/api/v1/project/circleci/circle/43557"}), true)

 j.it "CSRF token should NOT present on requests to S3", ->
   @expect(CI.sendCSRFtoken({url: "https://circle-development-action-output.s3.amazonaws.com/aec9f023a9924003a4781725-circleci-circle-7-0?Expires=1542919505&AWSAccessKeyId=AKIAICVK7FVGWE6KDIIA&Signature=Ds9KuYYJuowyDpf9QdVKUTCmvE8%3D"}), false)
