import tempfile
import unittest
from pathlib import Path
from live_configuration import load_live_environment


class LiveConfigurationTest(unittest.TestCase):
    def load(self, text):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / ".env"
            file.write_text(text)
            return load_live_environment(file)

    def test_quotes_and_comments(self):
        self.assertEqual(self.load('# comment\nGOOGLE_API_KEY="local-test-value"\n')["GOOGLE_API_KEY"], "local-test-value")

    def test_no_shell_execution_or_expansion(self):
        self.assertEqual(self.load("GOOGLE_API_KEY='$(exit 99)$HOME'\n")["GOOGLE_API_KEY"], "$(exit 99)$HOME")

    def test_empty_key_rejected_without_value_disclosure(self):
        with self.assertRaisesRegex(ValueError, "empty"):
            self.load("GOOGLE_API_KEY=\n")

    def test_invalid_lines_never_reveal_values(self):
        for text in ['WRONG=private-test-value', 'GOOGLE_API_KEY="private-test-value', 'GOOGLE_API_KEY=x\nGOOGLE_API_KEY=private-test-value']:
            with self.assertRaises(ValueError) as error:
                self.load(text)
            self.assertNotIn('private-test-value', str(error.exception))


if __name__ == "__main__":
    unittest.main()
