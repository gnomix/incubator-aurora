python_library(
  name = 'api',
  sources = ['__init__.py'],
  dependencies = [
    pants(':restarter'),
    pants(':scheduler_client'),
    pants(':updater'),
    pants('aurora/twitterdeps/src/python/twitter/common/lang'),
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('src/main/python/apache/aurora/common'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'command_runner',
  sources = ['command_runner.py'],
  dependencies = [
    pants('src/main/python/apache/aurora/BUILD.thirdparty:pystachio'),
    pants(':api'),
    pants('src/main/python/apache/thermos/config:schema'),
    pants('src/main/python/apache/aurora/common:cluster'),
    pants('src/main/python/apache/aurora/config:schema'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'disambiguator',
  sources = ['disambiguator.py'],
  dependencies = [
    pants(':api'),
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('src/main/python/apache/aurora/client:base'),
    pants('src/main/python/apache/aurora/common'),
  ]
)

python_library(
  name = 'job_monitor',
  sources = ['job_monitor.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/quantity'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'restarter',
  sources = ['restarter.py'],
  dependencies = [
    pants(':instance_watcher'),
    pants(':updater_util'),
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'scheduler_client',
  sources = ['scheduler_client.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('aurora/twitterdeps/src/python/twitter/common/quantity'),
    pants('aurora/twitterdeps/src/python/twitter/common/rpc/transports:tsslsocket'),
    pants('aurora/twitterdeps/src/python/twitter/common/zookeeper/serverset:kazoo_serverset'),
    pants('aurora/twitterdeps/src/python/twitter/common/zookeeper:kazoo_client'),
    pants('src/main/python/apache/aurora/common/auth'),
    pants('src/main/python/apache/aurora/common:cluster'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'instance_watcher',
  sources = ['instance_watcher.py', 'health_check.py'],
  dependencies = [
    pants(':scheduler_client'),
    pants('aurora/twitterdeps/src/python/twitter/common/lang'),
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('src/main/python/apache/aurora/common:http_signaler'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'updater',
  sources = ['updater.py'],
  dependencies = [
    pants(':scheduler_client'),
    pants(':instance_watcher'),
    pants(':updater_util'),
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'updater_util',
  sources = ['updater_util.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
  ]
)
