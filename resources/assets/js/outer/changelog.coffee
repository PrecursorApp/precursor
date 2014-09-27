CI.outer.Changelog = class Changelog extends CI.outer.Page

  constructor: ->
    super
    @name = "changelog"
    @entries = ko.observableArray([])

  author_exists: (author) =>
    VM.about.team[author]?

  image: (author) =>
    VM.about.team[author].img_path

  author: (author) =>
    VM.about.team[author].name

  twitter: (author) =>
    url = VM.about.team[author].twitter
    "https://twitter.com/#{url}"

  render: (cx) =>
    @fetchContent()
    super cx

  fetchContent: () =>
    $.ajax
      url: "/changelog.rss"
      type: "GET"
      dataType: "xml"
      success: (results) =>
        @entries(@doc2entries(results))
        @reloadHash()

   reloadHash: () =>
    # The hash is created after the page loads, which I think is why we need this.
    window.SammyApp.setLocation("/changelog" + window.location.hash)

  doc2entries: (document) =>
    for i in $(document).find("item")
      elem = $(i)
      title: elem.find('title').text()
      description: elem.find('description').text()
      link: elem.find('link').text()
      author: elem.find('author').text()
      pubDate: elem.find('pubDate').text()
      guid: elem.find('guid').text()
      type: elem.find('type').text()
      categories:
        for c in elem.find('category')
          $(c).text()
