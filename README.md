#ReactiveMongo Access Controller

`ReactiveMongoAccessController` is a library built on top of the [`ReactiveMongo`](https://github.com/ReactiveMongo/ReactiveMongo) driver, giving you a safe and easy way to check the access rights on your mongodb data.

It implements a minimalist users, user groups and sessions handling.

Most of the API has been taken from the `ReactiveMongo` API so you can use it the same way you are used to do.

You can find the API documentation [here](http://trupin.github.io/ReactiveMongoAccessController/releases/nightly/api/#package)

##Project status
The project is still in development, but I am actually working hard on it in order to use it for several of my personnal projects, so it should be released soon.

Most of the API methods are actually tested, so you can give it a try. However, the sources are still growing fast so take care keeping it up to date.

##Insttalation
The sonatype repository is not yet opened, but you always can deploy it locally by cloning it and following these few steps:

* `sbt publish-local`
* then add `"com.github.trupin" %% "reactivemongoaccesscontroller" % "0.1-SNAPSHOT"` in your project dependencies
