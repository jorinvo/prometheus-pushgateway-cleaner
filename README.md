# prometheus-pushgateway-cleaner

*Delete old metric jobs from [Prometheus](https://prometheus.io/) [pushgateway](https://github.com/prometheus/pushgateway)*

## Status

Archived since not actively in use. Feel free to fork.

## Alternatives

Instead of relying on pushgateway give [pushprox](https://github.com/robustperception/pushprox) a try to get around firewalls.

If you need to work with the pushgateway and you need expiration, then `prometheus-pushgateway-cleaner` is here to help.

## Intro

***This is a giant hack. Use with caution 🔥***

The [pushgateway](https://github.com/prometheus/pushgateway) mentions in its README:

> A while ago, we [decided to not implement a “timeout” or TTL for pushed metrics](https://github.com/prometheus/pushgateway/issues/19) because almost all proposed use cases turned out to be anti-patterns we strongly discourage. You can follow a more recent discussion on the [prometheus-developers mailing list](https://groups.google.com/forum/#!topic/prometheus-developers/9IyUxRvhY7w).

Please double-check that what you are doing is a good idea and there is no better solution to your problem!

...

So you are still here?

Unfortunately there are scenarios where you do not really have a choice but to do something non-optimal.

What if you are happily using Prometheus for monitoring and alerting for many different systems
and now suddenly you have a class of systems which run on hardware you do not control, behind a firewall you do not control?
What if this firewall only allows you to send metrics out and denies incoming requests?
What if this class of systems are ephemeral jobs which come and go?
Do you need to keep all of these metrics in the pushgateway forever?

While this might not be the scenario the pushgateway is build for and maybe not even the ideal environment for Prometheus itself,
it would be great if they can be a good enough solution for now and since you invested a lot in this setup already and rather not have a completely separate setup for this one use case.

Luckily it is *fine* that there is no TTL (time-to-live) / expiration built into the pushgateway.
It provides you with everything so you can build your own expiration strategy on top of it.

*And this is exactly what `prometheus-pushgateway-cleaner` does.*

`prometheus-pushgateway-cleaner` can be used to regularly delete expired metric jobs from the pushgateway.

You can configure it to cleanup in an interval or you can use your own scheduler to call it.

### How does it work?

`prometheus-pushgateway-cleaner` parses the metrics the pushgateway exposes
and uses the `push_time_seconds` metric to determine if a job is expired.

Then [DELETE](https://github.com/prometheus/pushgateway#delete-method) requests are sent to the pushgateway API accordingly.

### Why not fork pushgateway?

There have been many discussions around this feature and the Prometheus team made a very reasonable decision to not include this often misused feature into the actual pushgateway.

Instead, they expose all the tools you need to implement your own cleanup logic.

*`prometheus-pushgateway-cleaner` is only one possible solution.*

Multiple people forked the project and implemented a TTL feature. Non of them are up to date with the current version of the pushgateway anymore.

The premise of `prometheus-pushgateway-cleaner` is that separating concerns is much more sensible approach.


## Setup

The easiest way to run `prometheus-pushgateway-cleaner` is using the Docker image,

[available on dockerhub](https://hub.docker.com/r/jorinvo/prometheus-pushgateway-cleaner/tags)

```
docker run -it jorinvo/prometheus-pushgateway-cleaner
```

The images is around 12MB, starts up fast and uses little memory thanks to [GraalVM](https://www.graalvm.org/).

If you like to run the image on the JVM from source directly, [see below](running-the-application).

### Configuration

The image is configured using command line arguments:

```
    --metric-url METRIC_URL                               (REQUIRED) URI of the metric endpoint to crawl. Probably ends with /metrics/
    --expiration-in-minutes DURATION  60                  Jobs not updated longer than the specified time will be deleted
    --basic-auth BASIC_AUTH                               Request header(s)
    --interval-in-minutes INTERVAL                        When set, process keeps running and repeats check after interval time
    --dry-run                                             Log results but don't delete anything
    --report-metrics                                      Push success-metric to pushgateway which contains a unix timestamp (in s) for the last sucessful cleaning
lly
    --success-metric                                      Job name of the metric to push to pushgateway if --report-metrics is set
-s, --silent                                              Print nothing
-v, --version                                             Print version
-h, --help                                                Print this message
```


## Development

`prometheus-pushgateway-cleaner` is developed in [Clojure](https://clojure.org/).

For developing in your editor a Cider [nREPL](https://github.com/clojure-emacs/cider-nrepl) can be started with:

```
clj -Adev
```

### Testing

Invoke tests from your editor or run all tests with:

```
clj -Atest
```

### Running the application

The program can be run directly with

```
clj -Arun
```

### Building the application

To build a binary using [GraalVM](https://www.graalvm.org/), run the following command,
but set the path to your `GRAALVM_HOME` installation and your version:

```
GRAALVM_HOME=/Users/myuser/graalvm-ce-java11-19.3.1/Contents/Home clj -Anative-image -Dversion=myversion
```

### Releasing

To release a new version, create a new git tag and the Github action will do its magic.


## License

[MIT](./license.txt)
