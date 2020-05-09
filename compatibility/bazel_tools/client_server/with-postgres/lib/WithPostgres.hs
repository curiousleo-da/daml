-- Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

module WithPostgres (withPostgres) where

import Control.Exception.Safe
import Data.Text (Text)
import qualified Data.Text as T
import Network.Socket
import System.Directory.Extra
import System.FilePath
import System.IO.Extra
import System.Process

-- This is modelled after com.daml.testing.postgresql.PostgresAround.
-- We make this a separate executable since it should only
-- depend on released artifacts so we cannot easily use sandbox as a library.

postgresConfig :: PortNumber -> Text
postgresConfig port = T.unlines
    [ "unix_socket_directories = '/tmp'"
    , "shared_buffers = 12MB"
    , "fsync = off"
    , "synchronous_commit = off"
    , "full_page_writes = off"
    , "log_min_duration_statement = 0"
    , "log_connections = on"
    , "listen_addresses = 'localhost'"
    , "port = " <> T.pack (show port)
    ]

dbUser :: Text
dbUser = "test"

dbName :: Text
dbName = "test"

jdbcUrl :: PortNumber -> Text
jdbcUrl port =
    "jdbc:postgresql://localhost:" <>
    T.pack (show port) <>
    "/" <> dbName <> "?user=" <> dbName

-- Launch a temporary postgres instance and provide a jdbc url to access that database.
withPostgres :: (Text -> IO a) -> IO a
withPostgres f =
    withTempDir $ \tmpDir -> do
    let dataDir = tmpDir </> "data"
    let logFile = tmpDir </> "postgresql.log"
    createDirectory dataDir
    -- For reasons I don’t entirely understand, `locateRunfiles` does not
    -- work here. Hardcoding the paths to external/... matches what we do in
    -- com.daml.testing.postgresql.Tool.
    callProcess
        "external/postgresql_nix/bin/initdb"
        [ "--username=" <> T.unpack dbUser
        , dataDir
        , "--locale=en_US.UTF-8"
        , "-E", "UNICODE"
        , "-A", "trust"
        ]
    port <- getFreePort
    writeFileUTF8 (dataDir </> "postgresql.conf") (T.unpack $ postgresConfig port)
    bracket_ (startPostgres dataDir logFile) (stopPostgres dataDir) $ do
      createDatabase port
      f (jdbcUrl port)
  where startPostgres dataDir logFile =
            callProcess
                "external/postgresql_nix/bin/pg_ctl"
                ["-w", "-D", dataDir, "-l", logFile, "start"]
            `catchIO` (\e -> do
                postgresLog <- readFileUTF8 logFile
                hPutStrLn stderr $ unlines
                    [ "Postgres failed to start, log output:"
                    , postgresLog
                    ]
                throwIO e)
        stopPostgres dataDir =
            callProcess
                "external/postgresql_nix/bin/pg_ctl"
                ["-w", "-D", dataDir, "-m", "immediate", "stop"]
        createDatabase port =
            callProcess
                "external/postgresql_nix/bin/createdb"
                [ "-h", "localhost"
                , "-U", T.unpack dbUser
                , "-p", show port
                , T.unpack dbName
                ]


-- | This is somewhat racy since the port could be allocated
-- by another process in between the kernel providing it here
-- and postgres starting. However, it is better than simply
-- hardcoding the port. Postgres doesn’t seem to have an easy
-- option for starting on an arbitrary free port while
-- providing the actual port to us.
getFreePort :: IO PortNumber
getFreePort = do
    addr : _ <- getAddrInfo
        (Just socketHints)
        (Just "127.0.0.1")
        (Just "0")
    bracket
        (socket (addrFamily addr) (addrSocketType addr) (addrProtocol addr))
        close
        (\s -> do bind s (addrAddress addr)
                  name <- getSocketName s
                  case name of
                      SockAddrInet p _ -> pure p
                      _ -> fail $ "Expected a SockAddrInet but got " <> show name)

socketHints :: AddrInfo
socketHints = defaultHints { addrFlags = [AI_NUMERICHOST, AI_NUMERICSERV], addrSocketType = Stream }