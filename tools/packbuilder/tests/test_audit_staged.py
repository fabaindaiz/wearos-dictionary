"""The audit over what is STAGED, which is what a commit will hold (i-a2f271-e61126).

A series split from a full working tree passed the gate and still left a red commit: the
documents' test counts follow the working tree, so the first commit carried numbers that matched
the second's tests. The rule was written in the `commit` skill and was broken with it loaded; this
is the rung above it.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

import audit_dictionary  # noqa: E402


def _git(repo, *args):
    return subprocess.run(["git", "-C", repo] + list(args), capture_output=True, text=True,
                          check=True).stdout


class LoStagedTest(unittest.TestCase):

    def setUp(self):
        self.repo = tempfile.mkdtemp()
        _git(self.repo, "init", "-q")
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("staged\n")
        _git(self.repo, "add", "a.txt")
        # The working tree moves on after staging: the commit would hold "staged", not this.
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("working tree\n")
        with open(os.path.join(self.repo, "b.txt"), "w") as f:
            f.write("never staged\n")

    def tearDown(self):
        shutil.rmtree(self.repo, ignore_errors=True)

    def test_the_snapshot_holds_what_the_commit_would(self):
        destino = tempfile.mkdtemp()
        try:
            audit_dictionary.exportar_lo_staged(self.repo, destino)
            with open(os.path.join(destino, "a.txt")) as f:
                self.assertEqual("staged\n", f.read())
            self.assertFalse(os.path.exists(os.path.join(destino, "b.txt")))
        finally:
            shutil.rmtree(destino, ignore_errors=True)


class HookInstaladoTest(unittest.TestCase):

    def setUp(self):
        self.repo = tempfile.mkdtemp()
        _git(self.repo, "init", "-q")

    def tearDown(self):
        shutil.rmtree(self.repo, ignore_errors=True)

    def test_a_clone_without_the_hook_path_is_reported(self):
        self.assertFalse(audit_dictionary.hook_de_commit_instalado(self.repo))

    def test_fix_installs_it(self):
        audit_dictionary.instalar_hook_de_commit(self.repo)
        self.assertTrue(audit_dictionary.hook_de_commit_instalado(self.repo))
        self.assertEqual(".githooks\n", _git(self.repo, "config", "--get", "core.hooksPath"))


class ElHookExisteTest(unittest.TestCase):

    def test_the_hook_runs_the_staged_audit(self):
        raiz = audit_dictionary.ROOT
        ruta = os.path.join(raiz, ".githooks", "pre-commit")
        self.assertTrue(os.access(ruta, os.X_OK), "the hook must be executable")
        with open(ruta) as f:
            self.assertIn("audit_dictionary.py --staged", f.read())


if __name__ == "__main__":
    unittest.main()
