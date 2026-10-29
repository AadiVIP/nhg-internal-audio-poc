#!/usr/bin/env python3
"""Extract readable text from saved developer.android.com HTML pages."""
import sys
from bs4 import BeautifulSoup


def main(path: str) -> None:
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        soup = BeautifulSoup(f.read(), "lxml")
    for tag in soup(["script", "style", "noscript", "nav", "header", "footer"]):
        tag.decompose()
    main_body = (
        soup.find("article")
        or soup.find("div", class_="devsite-article-body")
        or soup.find("main")
        or soup.body
    )
    text = main_body.get_text("\n", strip=True) if main_body else ""
    text = "\n".join(line for line in text.splitlines() if line.strip())
    print(text)


if __name__ == "__main__":
    main(sys.argv[1])
