// The artifact id (= repo name) comes from brand.json, the one place the brand lives.
val brand = groovy.json.JsonSlurper().parse(file("brand.json")) as Map<*, *>
rootProject.name = "${brand["repoPrefix"]}-${brand["package"]}"
