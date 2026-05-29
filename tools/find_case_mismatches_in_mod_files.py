import re
from pathlib import Path
from typing import List, Tuple

import ahocorasick
import pandas as pd


def find_case_mismatches_in_mod_files(root_dir: Path | str) -> None:
    """
    Identifies case mismatches in mod files by renaming files where all references
    to a file name are identical but differ in capitalization. Does not identify filename references in compiled jars.

    :param root_dir: The root directory where the mod files are located.
    :return: None. Prints information about detected case mismatches.
    """
    root_dir = Path(root_dir).resolve()
    if not root_dir.exists():
        print(f"Provided path {root_dir} does not exist; exiting.")
        raise FileNotFoundError(f"Provided path {root_dir} does not exist.")
    if not root_dir.is_dir():
        print(f"Provided path {root_dir} is not a directory; exiting.")
        raise NotADirectoryError(f"Provided path {root_dir} is not a directory.")
    print(
        f"Checking file references for mod in {root_dir} for mismatches that may cause errors on case-sensitive filesystems."
    )
    file_df = _gen_file_df(root_dir)
    ref_df = _gen_ref_df(root_dir, file_df)
    file_df = _add_refs_to_file_df(file_df, ref_df)
    mismatches = _filter_no_ops(file_df)
    if mismatches.shape[0]:
        print(f"{mismatches.shape[0]} files found with mismatched case in one or more references.")
    else:
        print("No file references found with mismatched case relative to corresponding file names.")
        return
    for _, row in mismatches.iterrows():
        print(f"File: {row['file_name']}, Refs: {row['refs']}")


def _gen_file_df(root_dir: Path) -> pd.DataFrame:
    """
    Generates a DataFrame containing file paths and file names for all files
    in the specified root directory.

    :param root_dir: The root directory to search for files.
    :return: A DataFrame with columns:
        - 'file_path': The relative path of each file from the root directory.
        - 'file_name': The name of each file.
    """
    file_data = (
        (str(path.relative_to(root_dir)), path.name)
        for path in root_dir.rglob("*")
        if path.is_file()
    )
    file_df = pd.DataFrame(file_data, columns=["file_path", "file_name"])
    return file_df


def _build_aho_automaton(file_df: pd.DataFrame) -> ahocorasick.Automaton:
    """
    Builds an Aho-Corasick automaton from the file names in the provided DataFrame.

    :param file_df: A DataFrame containing the file names to be added to the automaton.
    :return: An Aho-Corasick automaton with all file names from the DataFrame as patterns.
    """
    automaton = ahocorasick.Automaton()
    for index, row in file_df.iterrows():
        automaton.add_word(row["file_name"].lower(), (index, row["file_name"]))
    automaton.make_automaton()
    return automaton


def _search_references_in_file(
    file_path: Path, automaton: ahocorasick.Automaton
) -> List[Tuple[str, str]]:
    """
    Searches for references to patterns (file names) within the specified file using
    the Aho-Corasick automaton for efficient multi-pattern matching.

    :param file_path: Path of the file to search within.
    :param automaton: Aho-Corasick automaton built from the file names to search for.
    :return: A list of tuples where each tuple contains:
        - The original file name (pattern) being referenced.
        - The exact text of the matched reference from the file.
    """
    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        original_content = f.read()
    content = original_content.lower()  # Convert content to lowercase for case-insensitive matching
    matches = []
    for end_index, (file_index, original_file_name) in automaton.iter(content):
        start_index = end_index - len(original_file_name) + 1
        matched_text = original_content[start_index : end_index + 1]
        matches.append((original_file_name, matched_text))
    return matches


def _gen_ref_df(root_dir: Path, file_df: pd.DataFrame) -> pd.DataFrame:
    """
    Generates a DataFrame containing references to file names within other files
    based on the specified root directory and file DataFrame.

    :param root_dir: The root directory where the files are located.
    :param file_df: A DataFrame containing file paths and names to be searched.
    :return: A DataFrame with columns:
        - 'name_referenced': The name of the file being referenced.
        - 'path_of_reference': The file path where the reference was found.
        - 'text_of_reference': The exact text of the reference found in the file.
        - 'is_consistent': A boolean indicating whether 'name_referenced' and
          'text_of_reference' are exactly identical, considering case.
    """
    automaton = _build_aho_automaton(file_df)
    ref_data: List[Tuple[str, str, str, bool]] = []
    exclude_from_ref_search = re.compile(r".*(\.png|\.ogg|\.jpg|\.jar)")
    for _, search_row in file_df.iterrows():
        search_path = root_dir / search_row["file_path"]
        if search_path.is_file() and not exclude_from_ref_search.match(search_path.name):
            references = _search_references_in_file(search_path, automaton)
            ref_data.extend(
                (
                    original_file_name,
                    str(search_row["file_path"]),
                    matched_text,
                    original_file_name == matched_text,
                )
                for original_file_name, matched_text in references
            )

    ref_df = pd.DataFrame(
        ref_data,
        columns=["name_referenced", "path_of_reference", "text_of_reference", "is_consistent"],
    )
    return ref_df


def _add_refs_to_file_df(file_df: pd.DataFrame, ref_df: pd.DataFrame) -> pd.DataFrame:
    """
    Adds 'refs' and 'len_refs' columns to file_df:
    - 'refs' contains a set of unique text_of_reference values from ref_df for each file_name in file_df.
    - 'len_refs' contains the number of strings in the 'refs' set for each file_name.

    :param file_df: DataFrame containing file paths and file names.
    :param ref_df: DataFrame containing reference data (name_referenced and text_of_reference).
    :return: Updated file_df with additional 'refs' and 'len_refs' columns.
    """
    refs_dict = (
        ref_df.groupby("name_referenced")["text_of_reference"]
        .apply(lambda refs: set(refs))
        .to_dict()
    )
    file_df["refs"] = file_df["file_name"].map(lambda name: refs_dict.get(name, set()))
    file_df["len_refs"] = file_df["refs"].apply(len)
    return file_df


def _filter_no_ops(file_df: pd.DataFrame) -> pd.DataFrame:
    """
    Filters out rows from file_df where len_refs is 0 or where len_refs is 1
    and the only element in refs is an exact case-sensitive match to file_name.

    :param file_df: DataFrame containing file paths, file names, refs, and len_refs.
    :return: A filtered DataFrame with the rows meeting the criteria removed.
    """

    def _should_keep_row(row) -> bool:
        # Keep rows where len_refs > 1 or len_refs == 1 but the refs element doesn't match file_name
        if row["len_refs"] == 0:
            return False
        if row["len_refs"] == 1 and row["file_name"] == next(iter(row["refs"])):
            return False
        return True

    return file_df[file_df.apply(_should_keep_row, axis=1)]
