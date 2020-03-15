# prometheus-pushgateway-cleaner

## Intro

***This is a giant hack. Use with caution 🔥***

The [pushgateway](https://github.com/prometheus/pushgateway) mentions in its README:

> A while ago, we [decided to not implement a “timeout” or TTL for pushed metrics](https://github.com/prometheus/pushgateway/issues/19) because almost all proposed use cases turned out to be anti-patterns we strongly discourage. You can follow a more recent discussion on the [prometheus-developers mailing list](https://groups.google.com/forum/#!topic/prometheus-developers/9IyUxRvhY7w).

Please double-check that what you are doing is a good idea and there is no better solution to your problem!

...

So you are still here?

Unfortunately there are scenarios where you do not really have a choice but to do something non-optimal.

What if you are happily using Prometheus for monitoring and alerting for many different systems
and now suddenly you have a class of systems which run on hardware you do not control, behind a firewall you do not control.
What if this firewall only allows you to send metrics out and denies incoming requests?
What if this class of systems are still dynamic and come and go?
Do you need to keep all of these metrics in the pushgateway forever?

While this might not be the scenario the pushgateway is build for and maybe not even the ideal environment for Prometheus itself,
maybe they are a good enough solution for now and what you invested in this setup might be worth sticking with it, for now.

And it is *okay* that there is no time-to-live or expiration built into the pushgateway.
It still allows you to build your own expiration strategy on top of it.

*And this is exactly what `prometheus-pushgateway-cleaner` does.*

`prometheus-pushgateway-cleaner` can be used to regularly delete expired metric jobs from the pushgateway.

You can configure it to cleanup in an interval or you can use your own scheduler to call it.


## Setup

### Installation

### Configuration



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
but set the bath to your `GRAALVM_HOME` installation:

```
GRAALVM_HOME=/Users/myuser/graalvm-ce-java11-19.3.1/Contents/Home clj -Anative-image
```


## License

[MIT](./license.txt)
