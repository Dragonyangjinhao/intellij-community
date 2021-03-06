# Stubs for boto.s3.multidelete (Python 3.5)
#
# NOTE: This dynamically typed stub was automatically generated by stubgen.

from typing import Any, Optional

class Deleted:
    key = ...  # type: Any
    version_id = ...  # type: Any
    delete_marker = ...  # type: Any
    delete_marker_version_id = ...  # type: Any
    def __init__(self, key: Optional[Any] = ..., version_id: Optional[Any] = ..., delete_marker: bool = ..., delete_marker_version_id: Optional[Any] = ...) -> None: ...
    def startElement(self, name, attrs, connection): ...
    def endElement(self, name, value, connection): ...

class Error:
    key = ...  # type: Any
    version_id = ...  # type: Any
    code = ...  # type: Any
    message = ...  # type: Any
    def __init__(self, key: Optional[Any] = ..., version_id: Optional[Any] = ..., code: Optional[Any] = ..., message: Optional[Any] = ...) -> None: ...
    def startElement(self, name, attrs, connection): ...
    def endElement(self, name, value, connection): ...

class MultiDeleteResult:
    bucket = ...  # type: Any
    deleted = ...  # type: Any
    errors = ...  # type: Any
    def __init__(self, bucket: Optional[Any] = ...) -> None: ...
    def startElement(self, name, attrs, connection): ...
    def endElement(self, name, value, connection): ...
