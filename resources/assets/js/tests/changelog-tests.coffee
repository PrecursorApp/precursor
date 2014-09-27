j.describe "changelog", ->
  j.it "should have correct twitter", ->
    @expect(CI.outer.changelog.twitter("pbiggar")).toEqual "https://twitter.com/pbiggar"
    @expect(CI.outer.changelog.twitter("notarealuser")).toThrow
