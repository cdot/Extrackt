#!/usr/bin/perl
# Recover .wav and track list for BBC radio programmes
# 0 10 * * * cd /massive/Music/Late* && perl ~/workspace/Extrackt/src/get_bbc.pl

use strict;

use LWP::UserAgent;
use JSON;
use Data::Dumper;
use Getopt::Long;
use FindBin;
use Pod::Usage;

my $get_iplayer = glob("~/Downloads/get_iplayer-2.94/get_iplayer");
my $episodes = [];
my ($help, $man, $force, $verbose);

# Shared user agent
our $ua = LWP::UserAgent->new();
$ua->agent('');
$ua->cookie_jar({ file => "$ENV{HOME}/get_lj_cookies.txt" });

sub remark {
    return unless $verbose;
    print STDERR "$$: " . join('', @_) . "\n";
}

# Given an episode pid, get track info
sub get_track_info {
    my $pid = shift;

    # Get episode information (segments)
    my $url = "http://www.bbc.co.uk/programmes/$pid.json";
    remark("Fetching track info for episode ", $pid, " from ", $url);
    my $response = $ua->get($url);
    die "Could not fetch info for $pid ".$response->status_line
        unless ($response->is_success());

    my $data = JSON::from_json($response->decoded_content());
    my $canonical_pid;
    foreach my $ver (@{$data->{programme}->{versions}}) {
        if ($ver->{canonical} == 1) {
            #print "$$:\tCanonical pid $ver->{pid}\n";
            $canonical_pid = $ver->{pid};
            last;
        }
    }

    die "Could not determine canonical pid\n" unless $canonical_pid;
    remark("\tFetching track data from ", $canonical_pid);
    $response = $ua->get("http://www.bbc.co.uk/programmes/$canonical_pid.json");
    die "Could not fetch info for $canonical_pid ".$response->status_line
        unless ($response->is_success());
    $data = JSON::from_json($response->decoded_content());

    # Analyse the segments to extract a track list
    my @tracks;
    my $offset = 0;
    foreach my $sege (@{$data->{version}->{segment_events}}) {
        my $seg = $sege->{segment};
        if ($seg->{type} eq 'music') {
            #print "$$: Got $seg->{artist} $seg->{duration}\n";
            my $track = {
                TPE1 => $seg->{artist},
                TIT2 => $seg->{track_title}  || $seg->{title},
                TALB => $seg->{release_title},
                _START => $offset,
                _END => $offset + $seg->{duration}
                 };
            $offset += $seg->{duration};
            foreach my $c (@{$seg->{contributions}}) {
                if ($c->{role} eq 'Composer') {
                    $track->{TCOM} = $c->{name};
                }
            }
            push(@tracks, $track);
        }
    }
    return \@tracks;
}

# Get the list of the pids of available episodes
sub get_episode_list {
    my $pid = shift;
    my $url = "http://www.bbc.co.uk/programmes/$pid/episodes/player.json";
    my $response = $ua->get($url);
    die "No episodes available: " . $response->status_line
        unless ($response->is_success());
    my $data = JSON::from_json($response->decoded_content());

    my @episodes;
    foreach my $episode (@{$data->{episodes}}) {
        $episode = $episode->{programme};
        next unless $episode;
        next if -e "$episode->{pid}.ignore";
        push(@episodes, $episode->{pid});
    }
    die "No episodes available" unless scalar(@episodes);
    return \@episodes;
}

sub make_tracks_list {
    my $pid = shift;
    my $tracks = get_track_info($pid);
    remark("Got ", $#$tracks, " tracks");
    my @lines;
    foreach my $track (@$tracks) {
        push(@lines, "$track->{_START} .. $track->{_END}");
        foreach my $k ( grep { !/^_/ } keys %$track ) {
            push(@lines, "$k: $track->{$k}");
        }
        push(@lines, "");
    }
    my $f;
    open($f, '>', "$pid.tracks");
    print $f join("\n", @lines)."\n";
    close($f);
}

sub shell {
    remark(join(' ', @_));
    my $result = system(@_);
    return 1 unless $result;
    die join(' ', @_)." exited with non-zero $result\n";
}

sub writefile {
    my ($f, $text) = @_;
    my $fd;
    open($fd, '>', $f) || die $!;
    print $fd $text;
    close($fd);
}

my $programme_pid = 'b006tp52'; # Late Junction
Getopt::Long::GetOptions(
    'episode=s', sub {
        push(@$episodes, $_[1]);
    },
    'programme=s', sub {
        $programme_pid = $_[1];
    },
    'force' => \$force,
    'verbose' => \$verbose,
    'help|?' => \$help, man => \$man) or pod2usage(2);
pod2usage(1) if $help;
pod2usage(-exitval => 0, -verbose => 2) if $man;

# if there's no episode options, do all available episodes
unless (scalar(@$episodes)) {
    $episodes = get_episode_list($programme_pid);
}

# All actual work is done in a subprocess. We create a new process for
# each episode that needs work, using in_subprocess.
our $main_process = $$;

# If we are already in a subprocess, return true. Otherwise create a
# subprocess. Always returns false in the main process and true in the
# subprocess.

sub in_subprocess {
    return 1 if $$ != $main_process;
    if (fork()) {
        return 0;
    } else {
        remark("Started subprocess");
        return $$;
    }
}

# First make sure we have tracks
foreach my $episode (@$episodes) {
    unless (-e "$episode.tracks") {
        next unless in_subprocess();
        remark("Fetching tracks for ", $episode);
        make_tracks_list($episode);
    }

    # Now grab any missing flv for wav conversion
    my $flv = glob("*_${episode}_default.flv");
    unless (-e "$episode.wav") {
        unless ($flv) {
            next unless in_subprocess();

            # Download the flv
            print "$$: Fetching $episode.flv\n";
            my @params = (
                $get_iplayer,
                '--nocopyright' );
            push(@params, '--quiet') unless $verbose;
            push(@params,
                '--get',
                '--force',
                '--overwrite',
                '--type=radio',
                '--raw',
                '--pid', $episode);
            shell(@params);
        }
    }

    if (!-e "$episode.wav" && -e $flv) {
        next unless in_subprocess();

        remark("Converting ", $flv, " to .wav");
        shell('avconv',
              '-v', 'quiet',
              '-i', $flv,
              "$episode.wav");
        unlink($flv);
    }


    if (!-e "$episode.silences" && -e "$episode.wav") {
        next unless in_subprocess();

        print "$$: Extracting silences from $episode.wav\n";
        shell('java',
              '-cp', "$FindBin::Bin/../dist/Extrackt.jar",
              'extrackt.hushfinder.HushFinder',
              '--threshold', 250, 1,
              '--out', "$episode.silences",
              "$episode.wav");
    }

    # Don't process more episodes in the subprocess
    last if $$ != $main_process;
}

exit 0;
1;
__END__

=head1 NAME

get_bbc.pl

=head1 SYNOPSIS

Grab program and information from BBC website

Each episode on the BBC website has a unique ID, called here the "episode"
The process makes use of the following files for each episode

=over 8

=item B<episode>.tracks - track metadata, downloaded

=item *_B<episode>_default.flv - RTSMP dump

=item B<episode>.wav - wav file, created from .flv

=item B<episode>.wav - wav file, raw sound data

=item B<episode>.silences - calculated from .wav

=item B<episode>.ignore - empty file, process will ignore this PID if set

=back

You can always force processing of an episode using the B<--episode> switch.

=head1 OPTIONS

=over 8

=item B<--episode=pid> PID for the episode - you can have as many of these as you want

=item B<--programme=pid> PID for the programme (default is Late JunctionJ)

=item B<--verbose> describe what you are doing

=back

=cut


