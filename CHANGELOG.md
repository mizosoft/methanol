# Change Log

## Version 1.4.1

*26-9-2020*

* Updated dependencies.
* Fix (#25): Autodetect if a deflated stream is zlib-wrapped or not to not crash when some servers
  incorrectly send raw deflated bytes for the `deflate` encoding.

## Version 1.4.0

*27-7-2020*

* Multipart progress tracking.

## Version 1.3.0

*22-6-2020*

* Default read timeout in `Methanol` client.
* API for tracking upload/download progress.
* High-level client interceptors.

## Version 1.2.0

*1-5-2020*

* Reactive JSON adapters with Jackson and Reactor.
* Common `MediaType` constants.
* XML adapters with JAXB.

## Version 1.1.0

*17-4-2020* 

* First "main-stream" release.

## Version 1.0.0

*25-3-2020*

* Dummy release.
