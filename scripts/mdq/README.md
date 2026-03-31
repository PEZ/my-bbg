# bbg mdq

A faithful port of [Rust mdq](https://github.com/yshavit/mdq), *jq for markdown* to [Babashka](https://babashka.org/).

[bbg mdq](mdq.clj) is written against [Rust mdq's integration test suite](https://github.com/yshavit/mdq/tree/main/tests), and passes them all, with a few tests adapted to accommodate `babashka.cli` differences with whatever *Rust mdq* uses for command line parsing. The *bbg mdq* test runner downloads the TOML test definitions from *Rust mdq* and runs them, overshadowing with any local adaptations.

## Differences from Rust mdq

### EDN
*bbg mdq* supports EDN output:

```
❯ bbg mdq --output edn '# Differences' README.md mdq-rust.md
{:items
 [{:section
   {:depth 2,
    :title "Libraries used:",
    :body
    [{:list
      [{:item
        [{:paragraph
          "[nextjournal/markdown](https://github.com/nextjournal/markdown) (Babashka built-in)"}]}
       {:item
        [{:paragraph
          "Instaparse ([a patched version that supports Babashka](https://github.com/Engelberg/instaparse/pull/242))"}]}
       {:item
        [{:paragraph
          "[dakrone/cheshire](https://github.com/dakrone/cheshire) (Babashka built-in)"}]}]}
     {:paragraph "(Plus the usual Babashka scripting suspects.)"}
     {:paragraph
      "Instaparse is required via deps, so it is actually being interpreted. This is some 50X slower than the also interpreted manual parsing I first had, but it seems prudent to use a proper parser for the _mdq_ selectors. I'm speculating that a Babashka built-in Instaparse would beat my manual parser in performance. Not that it matters for _mdq_, because the selectors are almost always very little text, but anyway."}
     {:paragraph
      "Note that to use the Instaparse we also currently need to use `bb` from git, until next release is cut. (That's where `bbg bb` comes in. 😀)"}]}}]}
```

### Command line args

- **Dash-selectors need `--`**: Queries starting with `-` are misinterpreted as flags. We need to do `bbg mdq -- '- list item'` instead of `mdq '- list item'`.
- **Space-separated flags**: `-o plain`, no support for `-oplain`.

### Less code

*bbg mdq* is 3K lines of REPL-friendly Clojure code including unit tests. While *Rust mdq* is 16K lines of compile -> run -> hope Rust code without unit tests. 😀 Quite a few of the Clojure lines are about being faithful to *Rust mdq*'s PEST error messages, btw.

(I actually don't know if there is more going on in *Rust mdq* than what's needed to make the integration test suite pass, so this LOC comparison could be wildly apples to oranges.)

## Libraries used:

* [nextjournal/markdown](https://github.com/nextjournal/markdown) (Babashka built-in)
* Instaparse ([a patched version that supports Babashka](https://github.com/Engelberg/instaparse/pull/242))
* [dakrone/cheshire](https://github.com/dakrone/cheshire) (Babashka built-in)

(Plus the usual Babashka scripting suspects.)

Instaparse is required via deps, so it is actually being interpreted. This is some 50X slower than the also interpreted manual parsing I first had, but it seems prudent to use a proper parser for the *mdq* selectors. I'm speculating that a Babashka built-in Instaparse would beat my manual parser in performance. Not that it matters for *mdq*, because the selectors are almost always very little text, but anyway.

Note that to use the Instaparse we also currently need to use `bb` from git, until next release is cut. (That's where `bbg bb` comes in. 😀)

## Why?

1. I thought it would be a fun experiment (it was!)
2. I wanted to test the patched Instaparse with Babashka

## But useful?

Yes. It fits in any pipeline where you need to query Markdown with precision. And EDN is the right format for the output in many pipelines. Plus: The LLMs know how to use `mdq`, so giving them a faithful port like this just makes Markdown search things very smooth, and very token effective.

## Vibe coded?

Yes.

The *Rust mdq* integration tests made that a very nice experience. I had some opinions, but mostly relaxed about them, only fixing a few things I couldn't stand. When I said *we must do it with Instaparse* the clankers worked for about an hour more, including expanding the unit test coverage, leaning on the integration tests. Most of the time was spent when I also wanted to replace the manual command line parsing to using babashka.cli. I eventually took pity and said we could relax about the absolute faithfulness, by using local versions of some of the tests. I think most of the struggles with the CLI parsing was a vibe coding skill issue on my part.