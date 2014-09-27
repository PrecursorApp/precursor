<!--

title: 
last_updated: 

-->

<form class='clearfix form-search' id='searchDocs' method='ko' action='' data-bind='submit: VM.docs.searchArticles'>
  <input id='searchQuery' name='query' placeholder='What can we help you find?' data-bind='typeahead: { source: VM.docs.suggestArticles, updater: VM.docs.performDocSearch }' type='text'></input>
  <button type='submit'>

  </button>
</form>