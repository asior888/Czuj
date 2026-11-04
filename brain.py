import anthropic

import config

_client = anthropic.Anthropic(api_key=config.ANTHROPIC_API_KEY)
_history = []
MAX_TURNS = 10  # tyle ostatnich wymian pamiętamy


def ask(text, status=""):
    """Wysyła pytanie do modelu, zwraca krótką odpowiedź do przeczytania na głos.

    status: opcjonalna linia ze skanera, np. "Wykryte trackery: brak".
    """
    system = config.SYSTEM_PROMPT
    if status:
        system += "\nAktualny stan skanera: " + status
    _history.append({"role": "user", "content": text})
    del _history[:-MAX_TURNS * 2]
    reply = _client.messages.create(
        model=config.LLM_MODEL,
        max_tokens=config.LLM_MAX_TOKENS,
        system=system,
        messages=_history,
    )
    answer = reply.content[0].text.strip()
    _history.append({"role": "assistant", "content": answer})
    return answer


if __name__ == "__main__":
    print(ask("Kim jesteś i co robisz?"))
